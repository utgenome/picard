/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package net.sf.picard.analysis;

import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.util.CollectionUtil;
import net.sf.picard.util.Histogram;
import net.sf.picard.sam.ReservedTagConstants;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.io.IoUtil;
import net.sf.picard.metrics.*;
import net.sf.picard.analysis.AlignmentSummaryMetrics.Category;
import net.sf.picard.util.IlluminaUtil;
import net.sf.picard.util.Log;
import net.sf.samtools.*;
import net.sf.samtools.util.CoordMath;
import net.sf.samtools.util.SequenceUtil;
import net.sf.samtools.util.StringUtil;

import java.io.File;
import java.util.*;

/**
 * A command line tool to read a BAM file and produce standard alignment metrics that would be applicable to any alignment.  
 * Metrics to include, but not limited to:
 * <ul>
 * <li>Total number of reads (total, period, no exclusions)</li>
 * <li>Total number of PF reads (PF == does not fail vendor check flag)</li>
 * <li>Number of PF noise reads (does not fail vendor check and has noise attr set)</li>
 * <li>Total aligned PF reads (any PF read that has a sequence and position)</li>
 * <li>High quality aligned PF reads (high quality == mapping quality >= 20)</li>
 * <li>High quality aligned PF bases (actual aligned bases, calculate off alignment blocks)</li>
 * <li>High quality aligned PF Q20 bases (subset of above where base quality >= 20)</li>
 * <li>Median mismatches in HQ aligned PF reads (how many aligned bases != ref on average)</li>
 * <li>Reads aligned in pairs (vs. reads aligned with mate unaligned/not present)</li>
 * <li>Read length (how to handle mixed lengths?)</li>
 * <li>Bad Cycles - how many machine cycles yielded combined no-call and mismatch rates of >= 80%</li>
 * <li>Strand balance - reads mapped to positive strand / total mapped reads</li>
 * </ul>
 * Metrics are written for the first read of a pair, the second read, and combined for the pair.
 * 
 * @author Doug Voet (dvoet at broadinstitute dot org)
 */
public class CollectAlignmentSummaryMetrics extends SinglePassSamProgram {
    private static final int MAPPING_QUALITY_THRESHOLD = 20;
    private static final int BASE_QUALITY_THRESHOLD = 20;

    private static final int ADAPTER_MATCH_LENGTH = 16;
    private static final int MAX_ADAPTER_ERRORS = 1;
    private byte[][] ADAPTER_KMERS;
    private static final Log log = Log.getInstance(CollectAlignmentSummaryMetrics.class);

    final GroupAlignmentSummaryMetricsCollector allReadsCollector  = new GroupAlignmentSummaryMetricsCollector(null, null, null);
    final Map<String,GroupAlignmentSummaryMetricsCollector> sampleCollectors    = new HashMap<String,GroupAlignmentSummaryMetricsCollector>();
    final Map<String,GroupAlignmentSummaryMetricsCollector> libraryCollectors   = new HashMap<String,GroupAlignmentSummaryMetricsCollector>();
    final Map<String,GroupAlignmentSummaryMetricsCollector> readGroupCollectors = new HashMap<String,GroupAlignmentSummaryMetricsCollector>();

    // Usage and parameters
    @Usage
    public String USAGE = "Reads a SAM or BAM file and writes a file containing summary alignment metrics.\n";

    @Option(doc="Paired end reads above this insert size will be considered chimeric along with inter-chromosomal pairs.")
    public int MAX_INSERT_SIZE = 100000;

    @Option() public List<String> ADAPTER_SEQUENCE = CollectionUtil.makeList(
        IlluminaUtil.IlluminaAdapterPair.SINGLE_END.get5PrimeAdapter(),
        IlluminaUtil.IlluminaAdapterPair.SINGLE_END.get3PrimeAdapter(),
        IlluminaUtil.IlluminaAdapterPair.PAIRED_END.get5PrimeAdapter(),
        IlluminaUtil.IlluminaAdapterPair.PAIRED_END.get3PrimeAdapter(),
        IlluminaUtil.IlluminaAdapterPair.INDEXED.get5PrimeAdapter(),
        IlluminaUtil.IlluminaAdapterPair.INDEXED.get3PrimeAdapter()
    );

    @Option(shortName="LEVEL", doc="The level(s) at which to accumulate metrics.  ")
    private Set<MetricAccumulationLevel> METRIC_ACCUMULATION_LEVEL = CollectionUtil.makeSet(MetricAccumulationLevel.ALL_READS);

    @Option(shortName="BS", doc="Whether the SAM or BAM file consists of bisulfite sequenced reads.  ")
    public boolean IS_BISULFITE_SEQUENCED = false;

    private boolean doRefMetrics;
    private boolean calculateAll = false;
    private boolean calculateSample = false;
    private boolean calculateLibrary = false;
    private boolean calculateReadGroup = false;

    /** Required main method implementation. */
    public static void main(final String[] argv) {
        new CollectAlignmentSummaryMetrics().instanceMainWithExit(argv);
    }

    /** Silly method that is necessary to give unit test access to call doWork() */
    protected final int testDoWork() { return doWork(); }

    @Override protected void setup(final SAMFileHeader header, final File samFile) {
        prepareAdapterSequences();
        doRefMetrics = REFERENCE_SEQUENCE != null;
        IoUtil.assertFileIsWritable(OUTPUT);

        if (header.getSequenceDictionary().isEmpty()) {
            log.warn(INPUT.getAbsoluteFile() + " has no sequence dictionary.  If any reads " +
                    "in the file are aligned then alignment summary metrics collection will fail.");
        }

        calculateAll = METRIC_ACCUMULATION_LEVEL.contains(MetricAccumulationLevel.ALL_READS);
        calculateSample = METRIC_ACCUMULATION_LEVEL.contains(MetricAccumulationLevel.SAMPLE);
        calculateLibrary = METRIC_ACCUMULATION_LEVEL.contains(MetricAccumulationLevel.LIBRARY);
        calculateReadGroup = METRIC_ACCUMULATION_LEVEL.contains(MetricAccumulationLevel.READ_GROUP);

        for (SAMReadGroupRecord rg : header.getReadGroups()) {
            if (calculateSample) {
                if (!sampleCollectors.containsKey(rg.getSample())) {
                    sampleCollectors.put(rg.getSample(),
                        new GroupAlignmentSummaryMetricsCollector(rg.getSample(), null, null));
                }
            }
            if (calculateLibrary) {
                if (!libraryCollectors.containsKey(rg.getLibrary())) {
                    libraryCollectors.put(rg.getLibrary(),
                        new GroupAlignmentSummaryMetricsCollector(rg.getSample(), rg.getLibrary(), null));
                }
            }
            if (calculateReadGroup) {
                if (!readGroupCollectors.containsKey(rg.getPlatformUnit())) {
                    readGroupCollectors.put(rg.getPlatformUnit(),
                        new GroupAlignmentSummaryMetricsCollector(rg.getSample(), rg.getLibrary(), rg.getPlatformUnit()));
                }
            }
        }
    }

    @Override protected void acceptRead(final SAMRecord rec, final ReferenceSequence ref) {
        if (rec.getNotPrimaryAlignmentFlag()) return;
        final SAMReadGroupRecord rg = rec.getReadGroup();
        if (calculateAll) {
            allReadsCollector.addRecord(rec, ref);
        }
        if (calculateSample) {
            sampleCollectors.get(rg.getSample()).addRecord(rec, ref);
        }
        if (calculateLibrary) {
            libraryCollectors.get(rg.getLibrary()).addRecord(rec, ref);
        }
        if (calculateReadGroup) {
            readGroupCollectors.get(rg.getPlatformUnit()).addRecord(rec, ref);
        }
    }

    @Override protected void finish() {
        final List<Map<String,GroupAlignmentSummaryMetricsCollector>> collectorMaps =
                Arrays.asList(sampleCollectors, libraryCollectors, readGroupCollectors);

        final MetricsFile<AlignmentSummaryMetrics, Comparable<?>> file = getMetricsFile();

        if (METRIC_ACCUMULATION_LEVEL.contains(MetricAccumulationLevel.ALL_READS)) {
            allReadsCollector.addMetricsToFile(file);
        }

        for (final Map<String,GroupAlignmentSummaryMetricsCollector> collectorMap : collectorMaps) {
            for (final GroupAlignmentSummaryMetricsCollector collector : collectorMap.values()) {
                collector.addMetricsToFile(file);
            }
        }

        file.write(OUTPUT);
    }

    /** Converts the supplied adapter sequences to byte arrays in both fwd and rc. */
    private void prepareAdapterSequences() {
        final Set<String> kmers = new HashSet<String>();

        // Make a set of all kmers of ADAPTER_MATCH_LENGTH 
        for (final String seq : ADAPTER_SEQUENCE) {
            for (int i=0; i<=seq.length() - ADAPTER_MATCH_LENGTH; ++i) {
                final String kmer = seq.substring(i, i+ADAPTER_MATCH_LENGTH).toUpperCase();

                int ns = 0;
                for (final char ch : kmer.toCharArray()) if (ch == 'N') ++ns;
                if (ns <= MAX_ADAPTER_ERRORS) {
                    kmers.add(kmer);
                    kmers.add(SequenceUtil.reverseComplement(kmer));
                }
            }
        }
        
        // Make an array of byte[] for the kmers
        ADAPTER_KMERS = new byte[kmers.size()][];
        int i=0;        
        for (final String kmer : kmers) {
            ADAPTER_KMERS[i++] = StringUtil.stringToBytes(kmer);
        }
    }

    /**
     * Checks the first ADAPTER_MATCH_LENGTH bases of the read against known adapter sequences and returns
     * true if the read matches an adapter sequence with MAX_ADAPTER_ERRORS mismsatches or fewer.
     *
     * @param read the basecalls for the read in the order and orientation the machine read them
     * @return true if the read matches an adapter and false otherwise 
     */
    private boolean isAdapterSequence(final byte[] read) {
        if (read.length < ADAPTER_MATCH_LENGTH) return false;

        for (final byte[] adapter : ADAPTER_KMERS) {
            int errors = 0;

            for (int i=0; i<adapter.length; ++i) {
                if (read[i] != adapter[i]) {
                    if (++errors > MAX_ADAPTER_ERRORS) break;
                }
            }

            if (errors <= MAX_ADAPTER_ERRORS) return true;
        }

        return false;
    }

    private class GroupAlignmentSummaryMetricsCollector {
        final AlignmentSummaryMetricsCollector unpairedCollector;
        final AlignmentSummaryMetricsCollector firstOfPairCollector;
        final AlignmentSummaryMetricsCollector secondOfPairCollector;
        final AlignmentSummaryMetricsCollector pairCollector;
        final String sample;
        final String library;
        final String readGroup;

        public GroupAlignmentSummaryMetricsCollector(final String sample, final String library, final String readGroup) {
            this.sample = sample;
            this.library = library;
            this.readGroup = readGroup;
            unpairedCollector     = new AlignmentSummaryMetricsCollector(Category.UNPAIRED, sample, library, readGroup);
            firstOfPairCollector  = new AlignmentSummaryMetricsCollector(Category.FIRST_OF_PAIR, sample, library, readGroup);
            secondOfPairCollector = new AlignmentSummaryMetricsCollector(Category.SECOND_OF_PAIR, sample, library, readGroup);
            pairCollector         = new AlignmentSummaryMetricsCollector(Category.PAIR, sample, library, readGroup);
        }

        public void addRecord(final SAMRecord rec, final ReferenceSequence ref) {
            if (rec.getReadPairedFlag()) {
                if (rec.getFirstOfPairFlag()) {
                    firstOfPairCollector.addRecord(rec, ref);
                }
                else {
                    secondOfPairCollector.addRecord(rec, ref);
                }

                pairCollector.addRecord(rec, ref);
            }
            else {
                unpairedCollector.addRecord(rec, ref);
            }
        }

        public void addMetricsToFile(final MetricsFile<AlignmentSummaryMetrics, Comparable<?>> file) {
            // Let the collectors do any summary computations etc.
            unpairedCollector.onComplete();
            firstOfPairCollector.onComplete();
            secondOfPairCollector.onComplete();
            pairCollector.onComplete();

            if (firstOfPairCollector.getMetrics().TOTAL_READS > 0) {
                // override how bad cycle is determined for paired reads, it should be
                // the sum of first and second reads
                pairCollector.getMetrics().BAD_CYCLES = firstOfPairCollector.getMetrics().BAD_CYCLES +
                                                        secondOfPairCollector.getMetrics().BAD_CYCLES;

                file.addMetric(firstOfPairCollector.getMetrics());
                file.addMetric(secondOfPairCollector.getMetrics());
                file.addMetric(pairCollector.getMetrics());
            }

            //if there are no reads in any category then we will returned an unpaired alignment summary metric with all zero values
            if (unpairedCollector.getMetrics().TOTAL_READS > 0 || firstOfPairCollector.getMetrics().TOTAL_READS == 0) {
                file.addMetric(unpairedCollector.getMetrics());
            }
        }

    }

    /**
     * Class that counts reads that match various conditions
     */
    private class AlignmentSummaryMetricsCollector {
        private long numPositiveStrand = 0;
        private final Histogram<Integer> readLengthHistogram = new Histogram<Integer>();
        private AlignmentSummaryMetrics metrics;
        private long chimeras;
        private long chimerasDenominator;
        private long adapterReads;
        private long indels;

        private long nonBisulfiteAlignedBases = 0;
        private long hqNonBisulfiteAlignedBases = 0;
        private final Histogram<Long> mismatchHistogram = new Histogram<Long>();
        private final Histogram<Long> hqMismatchHistogram = new Histogram<Long>();
        private final Histogram<Integer> badCycleHistogram = new Histogram<Integer>();

        public AlignmentSummaryMetricsCollector(final Category pairingCategory,
                                                final String sample,
                                                final String library,
                                                final String readGroup) {
            metrics = new AlignmentSummaryMetrics();
            metrics.CATEGORY = pairingCategory;
            metrics.SAMPLE = sample;
            metrics.LIBRARY = library;
            metrics.READ_GROUP = readGroup;
        }

        public void addRecord(final SAMRecord record, final ReferenceSequence ref) {
            if (record.getNotPrimaryAlignmentFlag()) {
                // only want 1 count per read so skip non primary alignments
                return;
            }

            collectReadData(record, ref);
            collectQualityData(record, ref);
        }

        public void onComplete() {

            //summarize read data
            if (metrics.TOTAL_READS > 0)
            {
                metrics.PCT_PF_READS = (double) metrics.PF_READS / (double) metrics.TOTAL_READS;
                metrics.PCT_ADAPTER = this.adapterReads / (double) metrics.PF_READS;
                metrics.MEAN_READ_LENGTH = readLengthHistogram.getMean();

                //Calculate BAD_CYCLES
                metrics.BAD_CYCLES = 0;
                for (final Histogram<Integer>.Bin cycleBin : badCycleHistogram.values()) {
                    final double badCyclePercentage = cycleBin.getValue() / metrics.TOTAL_READS;
                    if (badCyclePercentage >= .8) {
                        metrics.BAD_CYCLES++;
                    }
                }

                if(doRefMetrics) {
                    if (metrics.PF_READS > 0)         metrics.PCT_PF_READS_ALIGNED = (double) metrics.PF_READS_ALIGNED / (double) metrics.PF_READS;
                    if (metrics.PF_READS_ALIGNED > 0) metrics.PCT_READS_ALIGNED_IN_PAIRS = (double) metrics.READS_ALIGNED_IN_PAIRS/ (double) metrics.PF_READS_ALIGNED;
                    if (metrics.PF_READS_ALIGNED > 0) metrics.STRAND_BALANCE = numPositiveStrand / (double) metrics.PF_READS_ALIGNED;
                    if (this.chimerasDenominator > 0) metrics.PCT_CHIMERAS = this.chimeras / (double) this.chimerasDenominator;

                    if (nonBisulfiteAlignedBases > 0) metrics.PF_MISMATCH_RATE = mismatchHistogram.getSum() / (double) nonBisulfiteAlignedBases;
                    metrics.PF_HQ_MEDIAN_MISMATCHES = hqMismatchHistogram.getMedian();
                    if (hqNonBisulfiteAlignedBases > 0) metrics.PF_HQ_ERROR_RATE = hqMismatchHistogram.getSum() / (double) hqNonBisulfiteAlignedBases;
                    if (metrics.PF_ALIGNED_BASES > 0) metrics.PF_INDEL_RATE = this.indels / (double) metrics.PF_ALIGNED_BASES;
                }
            }
        }

        private void collectReadData(final SAMRecord record, final ReferenceSequence ref) {
            metrics.TOTAL_READS++;
            readLengthHistogram.increment(record.getReadBases().length);

            if (!record.getReadFailsVendorQualityCheckFlag()) {
                metrics.PF_READS++;
                if (isNoiseRead(record)) metrics.PF_NOISE_READS++;

                if (record.getReadUnmappedFlag()) {
                    // If the read is unmapped see if it's adapter sequence
                    final byte[] readBases = record.getReadBases();
                    if (!(record instanceof BAMRecord)) StringUtil.toUpperCase(readBases);
                    
                    if (isAdapterSequence(readBases)) {
                        this.adapterReads++;
                    }
                }
                else if(doRefMetrics) {
                    metrics.PF_READS_ALIGNED++;
                    if (!record.getReadNegativeStrandFlag()) numPositiveStrand++;

                    if (record.getReadPairedFlag() && !record.getMateUnmappedFlag()) {
                        metrics.READS_ALIGNED_IN_PAIRS++;

                        // Check that both ends have mapq > minimum
                        final Integer mateMq = record.getIntegerAttribute("MQ");
                        if (mateMq == null || mateMq >= MAPPING_QUALITY_THRESHOLD && record.getMappingQuality() >= MAPPING_QUALITY_THRESHOLD) {
                            ++this.chimerasDenominator;

                            // With both reads mapped we can see if this pair is chimeric
                            if (Math.abs(record.getInferredInsertSize()) > MAX_INSERT_SIZE ||
                                 !record.getReferenceIndex().equals(record.getMateReferenceIndex())) {
                                ++this.chimeras;
                            }
                        }
                    }
                }
            }
        }

        private void collectQualityData(final SAMRecord record, final ReferenceSequence reference) {
            // If the read isnt an aligned PF read then look at the read for no-calls
            if (record.getReadUnmappedFlag() || record.getReadFailsVendorQualityCheckFlag() || !doRefMetrics) {
                final byte[] readBases = record.getReadBases();
                for (int i = 0; i < readBases.length; i++) {
                    if (SequenceUtil.isNoCall(readBases[i])) {
                        badCycleHistogram.increment(CoordMath.getCycle(record.getReadNegativeStrandFlag(), readBases.length, i));
                    }
                }
            }
            else if (!record.getReadFailsVendorQualityCheckFlag()) {
                final boolean highQualityMapping = isHighQualityMapping(record);
                if (highQualityMapping) metrics.PF_HQ_ALIGNED_READS++;

                final byte[] readBases = record.getReadBases();
                final byte[] refBases = reference.getBases();
                final byte[] qualities  = record.getBaseQualities();
                final int refLength = refBases.length;
                long mismatchCount   = 0;
                long hqMismatchCount = 0;

                for (final AlignmentBlock alignmentBlock : record.getAlignmentBlocks()) {
                    final int readIndex = alignmentBlock.getReadStart() - 1;
                    final int refIndex  = alignmentBlock.getReferenceStart() - 1;
                    final int length    = alignmentBlock.getLength();

                    for (int i=0; i<length && refIndex+i<refLength; ++i) {
                        final int readBaseIndex = readIndex + i;
                        boolean mismatch = !SequenceUtil.basesEqual(readBases[readBaseIndex], refBases[refIndex+i]);
                        boolean bisulfiteBase = false;
                        if (mismatch && IS_BISULFITE_SEQUENCED) {
                            if ( (record.getReadNegativeStrandFlag() &&
                                   (refBases[refIndex+i] == 'G' || refBases[refIndex+i] =='g') &&
                                   (readBases[readBaseIndex] == 'A' || readBases[readBaseIndex] == 'a'))
                                || ((!record.getReadNegativeStrandFlag()) &&
                                    (refBases[refIndex+i] == 'C' || refBases[refIndex+i] == 'c') &&
                                    (readBases[readBaseIndex] == 'T') || readBases[readBaseIndex] == 't') ) {

                                bisulfiteBase = true;
                                mismatch = false;
                            }
                        }

                        if(mismatch) mismatchCount++;

                        metrics.PF_ALIGNED_BASES++;
                        if(!bisulfiteBase) nonBisulfiteAlignedBases++;

                        if (highQualityMapping) {
                            metrics.PF_HQ_ALIGNED_BASES++;
                            if (!bisulfiteBase) hqNonBisulfiteAlignedBases++;
                            if (qualities[readBaseIndex] >= BASE_QUALITY_THRESHOLD) metrics.PF_HQ_ALIGNED_Q20_BASES++;
                            if (mismatch) hqMismatchCount++;
                        }

                        if (mismatch || SequenceUtil.isNoCall(readBases[readBaseIndex])) {
                            badCycleHistogram.increment(CoordMath.getCycle(record.getReadNegativeStrandFlag(), readBases.length, i));
                        }
                    }
                }

                mismatchHistogram.increment(mismatchCount);
                hqMismatchHistogram.increment(hqMismatchCount);

                // Add any insertions and/or deletions to the global count
                for (final CigarElement elem : record.getCigar().getCigarElements()) {
                    final CigarOperator op = elem.getOperator();
                    if (op == CigarOperator.INSERTION || op == CigarOperator.DELETION) ++ this.indels;
                }
            }
        }

        private boolean isNoiseRead(final SAMRecord record) {
            final Object noiseAttribute = record.getAttribute(ReservedTagConstants.XN);
            return (noiseAttribute != null && noiseAttribute.equals(1));
        }

        private boolean isHighQualityMapping(final SAMRecord record) {
            return !record.getReadFailsVendorQualityCheckFlag() &&
            record.getMappingQuality() >= MAPPING_QUALITY_THRESHOLD;
        }

        public AlignmentSummaryMetrics getMetrics() {
            return this.metrics;
        }
    }
}
