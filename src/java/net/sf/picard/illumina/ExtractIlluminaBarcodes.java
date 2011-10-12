/*
 * The MIT License
 *
 * Copyright (c) 2011 The Broad Institute
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
package net.sf.picard.illumina;

import net.sf.picard.illumina.parser.IlluminaDataProvider;
import net.sf.picard.util.Log;
import net.sf.picard.util.TabbedTextFileWithHeaderParser;
import net.sf.picard.PicardException;
import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.io.IoUtil;
import net.sf.picard.metrics.MetricBase;
import net.sf.picard.metrics.MetricsFile;
import net.sf.samtools.util.SequenceUtil;
import net.sf.samtools.util.StringUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.text.NumberFormat;

import net.sf.picard.illumina.parser.IlluminaDataProviderFactory;
import net.sf.picard.illumina.parser.IlluminaDataType;
import net.sf.picard.illumina.parser.ClusterData;

/**
 * Determine the barcode for each read in an Illumina lane.
 * For each tile, a file is written to the basecalls directory of the form s_<lane>_<tile>_barcode.txt.
 * An output file contains a line for each read in the tile, aligned with the regular basecall output
 * The output file contains the following tab-separated columns:
 * - read subsequence at barcode position
 * - Y or N indicating if there was a barcode match
 * - matched barcode sequence (empty if read did not match one of the barcodes).
 *
 * @author alecw@broadinstitute.org
 */
public class ExtractIlluminaBarcodes extends CommandLineProgram {

    // The following attributes define the command-line arguments
    @Usage
    public String USAGE =
        getStandardUsagePreamble() +  "Determine the barcode for each read in an Illumina lane.\n" +
                "For each tile, a file is written to the basecalls directory of the form s_<lane>_<tile>_barcode.txt." +
                "An output file contains a line for each read in the tile, aligned with the regular basecall output\n" +
                "The output file contains the following tab-separated columns: \n" +
                "    * read subsequence at barcode position\n" +
                "    * Y or N indicating if there was a barcode match\n" +
                "    * matched barcode sequence\n" +
                "Note that the order of specification of barcodes can cause arbitrary differences in output for poorly matching barcodes.\n\n";

    @Option(doc="Deprecated option; use BASECALLS_DIR", mutex = "BASECALLS_DIR")
    public File BUSTARD_DIR;

    @Option(doc="The Illumina basecalls output directory. ", mutex = "BUSTARD_DIR", shortName="B")
    public File BASECALLS_DIR;

    @Option(doc="Where to write _barcode.txt files.  By default, these are written to BASECALLS_DIR.", optional = true)
    public File OUTPUT_DIR;

    @Option(doc="Lane number. ", shortName= StandardOptionDefinitions.LANE_SHORT_NAME)
    public Integer LANE;

    @Option(doc="1-based cycle number of the start of the barcode.", shortName = "BARCODE_POSITION")
    public Integer BARCODE_CYCLE;

    @Option(doc="Barcode sequence.  These must be unique, and all the same length.", mutex = {"BARCODE_FILE"})
    public List<String> BARCODE = new ArrayList<String>();

    @Option(doc="Tab-delimited file of barcode sequences, and optionally barcode name and library name.  " +
            "Barcodes must be unique, and all the same length.  Column headers must be 'barcode_sequence', " +
            "'barcode_name', and 'library_name'.", mutex = {"BARCODE"})
    public File BARCODE_FILE;

    @Option(doc="Per-barcode and per-lane metrics written to this file.", shortName = StandardOptionDefinitions.METRICS_FILE_SHORT_NAME)
    public File METRICS_FILE;

    @Option(doc="Maximum mismatches for a barcode to be considered a match.")
    public int MAX_MISMATCHES = 1;

    @Option(doc="Minimum difference between number of mismatches in the best and second best barcodes for a barcode to be considered a match.")
    public int MIN_MISMATCH_DELTA = 1;

    @Option(doc="Maximum allowable number of no-calls in a barcode read before it is considered unmatchable.")
    public int MAX_NO_CALLS = 2;

    @Option(shortName="GZIP", doc="Compress output s_l_t_barcode.txt files using gzip and append a .gz extension to the filenames.")
    public boolean COMPRESS_OUTPUTS = false;

    private final Log log = Log.getInstance(ExtractIlluminaBarcodes.class);

    private int barcodeLength;

    private int tile = 0;
    private File barcodeFile = null;
    private BufferedWriter writer = null;

    private final List<NamedBarcode> namedBarcodes = new ArrayList<NamedBarcode>();

    private final List<BarcodeMetric> barcodeMetrics = new ArrayList<BarcodeMetric>();
    private BarcodeMetric noMatchBarcodeMetric;

    private final NumberFormat tileNumberFormatter = NumberFormat.getNumberInstance();

    static class BarcodeMatch {
        boolean matched;
        String barcode;
        int mismatches;
        int mismatchesToSecondBest;
    }

    public ExtractIlluminaBarcodes() {
        tileNumberFormatter.setMinimumIntegerDigits(4);
        tileNumberFormatter.setGroupingUsed(false);
    }

    @Override
	protected int doWork() {
        if(BUSTARD_DIR != null) {
            BASECALLS_DIR = BUSTARD_DIR;
        }
        
        IoUtil.assertDirectoryIsWritable(BASECALLS_DIR);
        IoUtil.assertFileIsWritable(METRICS_FILE);
        if (OUTPUT_DIR == null) {
            OUTPUT_DIR = BASECALLS_DIR;
        }
        IoUtil.assertDirectoryIsWritable(OUTPUT_DIR);

        for (final NamedBarcode namedBarcode : namedBarcodes) {
            barcodeMetrics.add(new BarcodeMetric(namedBarcode));
        }

        // Create BarcodeMetric for counting reads that don't match any barcode
        final StringBuilder noMatchBarcode = new StringBuilder(barcodeLength);
        for (int i = 0; i < barcodeLength; ++i) {
            noMatchBarcode.append('N');
        }
        noMatchBarcodeMetric = new BarcodeMetric(new NamedBarcode(noMatchBarcode.toString()));

        final IlluminaDataProviderFactory factory = new IlluminaDataProviderFactory(BASECALLS_DIR, LANE,
                BARCODE_CYCLE, barcodeLength, IlluminaDataType.BaseCalls, IlluminaDataType.PF);
        // This is possible for index-only run.
        factory.setAllowZeroLengthFirstEnd(true);
        final IlluminaDataProvider parser = factory.makeDataProvider();

        // Process each tile qseq file.
        try {
            while (parser.hasNext()) {
                final ClusterData cluster = parser.next();
                extractBarcode(cluster);
            }
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            throw new PicardException("IOException writing barcode file " + barcodeFile, e);
        }

        // Finish metrics tallying.
        int totalReads = noMatchBarcodeMetric.READS;
        int totalPfReads = noMatchBarcodeMetric.PF_READS;
        int totalPfReadsAssigned = 0;
        for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
            totalReads += barcodeMetric.READS;
            totalPfReads += barcodeMetric.PF_READS;
            totalPfReadsAssigned += barcodeMetric.PF_READS;
        }

        if (totalReads > 0) {
            noMatchBarcodeMetric.PCT_MATCHES = noMatchBarcodeMetric.READS/(double)totalReads;
            double bestPctOfAllBarcodeMatches = 0;
            for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
                barcodeMetric.PCT_MATCHES = barcodeMetric.READS/(double)totalReads;
                if (barcodeMetric.PCT_MATCHES > bestPctOfAllBarcodeMatches) {
                    bestPctOfAllBarcodeMatches = barcodeMetric.PCT_MATCHES;
                }
            }
            if (bestPctOfAllBarcodeMatches > 0) {
                noMatchBarcodeMetric.RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT =
                        noMatchBarcodeMetric.PCT_MATCHES/bestPctOfAllBarcodeMatches;
                for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
                    barcodeMetric.RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT =
                            barcodeMetric.PCT_MATCHES/bestPctOfAllBarcodeMatches;
                }
            }
        }

        if (totalPfReads > 0) {
            noMatchBarcodeMetric.PF_PCT_MATCHES = noMatchBarcodeMetric.PF_READS/(double)totalPfReads;
            double bestPctOfAllBarcodeMatches = 0;
            for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
                barcodeMetric.PF_PCT_MATCHES = barcodeMetric.PF_READS/(double)totalPfReads;
                if (barcodeMetric.PF_PCT_MATCHES > bestPctOfAllBarcodeMatches) {
                    bestPctOfAllBarcodeMatches = barcodeMetric.PF_PCT_MATCHES;
                }
            }
            if (bestPctOfAllBarcodeMatches > 0) {
                noMatchBarcodeMetric.PF_RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT =
                        noMatchBarcodeMetric.PF_PCT_MATCHES/bestPctOfAllBarcodeMatches;
                for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
                    barcodeMetric.PF_RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT =
                            barcodeMetric.PF_PCT_MATCHES/bestPctOfAllBarcodeMatches;
                }
            }
        }

        // Calculate the normalized matches
        if (totalPfReadsAssigned > 0) {
            final double mean = (double) totalPfReadsAssigned / (double) barcodeMetrics.size();
            for (final BarcodeMetric m : barcodeMetrics) {
                m.PF_NORMALIZED_MATCHES = m.PF_READS  / mean;
            }
        }

        final MetricsFile<BarcodeMetric, Integer> metrics = getMetricsFile();
        for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
            metrics.addMetric(barcodeMetric);
        }
        metrics.addMetric(noMatchBarcodeMetric);

        metrics.write(METRICS_FILE);
        return 0;
    }

    /**
     * Scan through qseqFile, and create a sibling barcode file with the barcode assignment lined up with the
     * tile's qseq file.
     */
    private void ensureBarcodeFileOpen(final int tile) {
        if (tile == this.tile) {
            return;
        }
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            this.tile = tile;
            barcodeFile = getBarcodeFile(tile);
            writer = IoUtil.openFileForBufferedWriting(barcodeFile);
            log.info("Extracting barcodes for tile " + tile);
        }
        catch (IOException e) {
            throw new PicardException("IOException " + barcodeFile, e);
        }
    }

    /**
     * Assign barcodes for a single tile's qseq file
     */
    private void extractBarcode(final ClusterData cluster) throws IOException {
        final String barcodeSubsequence = StringUtil.bytesToString(cluster.getBarcodeRead().getBases());
        final boolean passingFilter = cluster.isPf();
        final BarcodeMatch match = findBestBarcode(barcodeSubsequence, passingFilter);

        final String yOrN = (match.matched ? "Y" : "N");
        ensureBarcodeFileOpen(cluster.getTile());
        writer.write(StringUtil.join("\t", barcodeSubsequence, yOrN, match.barcode,
                                     String.valueOf(match.mismatches),
                                     String.valueOf(match.mismatchesToSecondBest)));
        writer.newLine();
    }

    /**
     * Find the best barcode match for the given read sequence, and accumulate metrics
     * @param readSubsequence portion of read containing barcode
     * @param passingFilter PF flag for the current read
     * @return perfect barcode string, if there was a match within tolerance, or null if not.
     */
    private BarcodeMatch findBestBarcode(final String readSubsequence, final boolean passingFilter) {
        BarcodeMetric bestBarcodeMetric = null;
        // PIC-506 When forcing all reads to match a single barcode, allow a read to match even if every
        // base is a mismatch.
        int numMismatchesInBestBarcode = readSubsequence.length() + 1;
        int numMismatchesInSecondBestBarcode = readSubsequence.length() + 1;

        final byte[] readBytes = net.sf.samtools.util.StringUtil.stringToBytes(readSubsequence);
        int numNoCalls = 0;
        for (final byte b : readBytes) if (SequenceUtil.isNoCall(b)) ++numNoCalls;


        for (final BarcodeMetric barcodeMetric : barcodeMetrics) {
            final int numMismatches = countMismatches(barcodeMetric.barcodeBytes, readBytes);
            if (numMismatches < numMismatchesInBestBarcode) {
                if (bestBarcodeMetric != null) {
                    numMismatchesInSecondBestBarcode = numMismatchesInBestBarcode;
                }
                numMismatchesInBestBarcode = numMismatches;
                bestBarcodeMetric = barcodeMetric;
            } else if (numMismatches < numMismatchesInSecondBestBarcode) {
                numMismatchesInSecondBestBarcode = numMismatches;
            }
        }

        final boolean matched = bestBarcodeMetric != null &&
                numNoCalls <= MAX_NO_CALLS &&
                numMismatchesInBestBarcode <= MAX_MISMATCHES &&
                numMismatchesInSecondBestBarcode - numMismatchesInBestBarcode >= MIN_MISMATCH_DELTA;

        final BarcodeMatch match = new BarcodeMatch();

        if (numNoCalls + numMismatchesInBestBarcode < readSubsequence.length()) {
            match.mismatches = numMismatchesInBestBarcode;
            match.mismatchesToSecondBest = numMismatchesInSecondBestBarcode;
            match.barcode = bestBarcodeMetric.BARCODE.toLowerCase();
        }
        else {
            match.mismatches = readSubsequence.length();
            match.mismatches = readSubsequence.length();
            match.barcode = "";
        }

        if (matched) {
            ++bestBarcodeMetric.READS;
            if (passingFilter) {
                ++bestBarcodeMetric.PF_READS;
            }
            if (numMismatchesInBestBarcode == 0) {
                ++bestBarcodeMetric.PERFECT_MATCHES;
                if (passingFilter) {
                    ++bestBarcodeMetric.PF_PERFECT_MATCHES;
                }
            } else if (numMismatchesInBestBarcode == 1) {
                ++bestBarcodeMetric.ONE_MISMATCH_MATCHES;
                if (passingFilter) {
                    ++bestBarcodeMetric.PF_ONE_MISMATCH_MATCHES;
                }
            }

            match.matched = true;
            match.barcode = bestBarcodeMetric.BARCODE;
        }
        else {
            ++noMatchBarcodeMetric.READS;
            if (passingFilter) {
                ++noMatchBarcodeMetric.PF_READS;
            }
        }

        return match;
    }

    /**
     * Compare barcode sequence to bases from read
     * @return how many bases did not match
     */
    private int countMismatches(final byte[] barcodeBytes, final byte[] readSubsequence) {
        int numMismatches = 0;
        for (int i = 0; i < barcodeBytes.length; ++i) {
            if (!SequenceUtil.isNoCall(readSubsequence[i]) && !SequenceUtil.basesEqual(barcodeBytes[i], readSubsequence[i])) {
                ++numMismatches;
            }
        }
        return numMismatches;
    }

    /**
     * Create a barcode filename corresponding to the given tile qseq file.
     */
    private File getBarcodeFile(final int tile) {
        return new File(OUTPUT_DIR,
                        "s_" + LANE + "_" + tileNumberFormatter.format(tile) + "_barcode.txt" + (COMPRESS_OUTPUTS ? ".gz" : ""));
    }

    /**
     * Validate that POSITION >= 1, and that all BARCODEs are the same length and unique
     *
     * @return null if command line is valid.  If command line is invalid, returns an array of error message
     *         to be written to the appropriate place.
     */
    @Override
    protected String[] customCommandLineValidation() {
        final ArrayList<String> messages = new ArrayList<String>();
        if (BARCODE_CYCLE < 1) {
            messages.add("Invalid BARCODE_POSITION=" + BARCODE_CYCLE + ".  Value must be positive.");
        }
        if (BARCODE_FILE != null) {
            parseBarcodeFile(messages);
        } else {
            final Set<String> barcodes = new HashSet<String>();
            barcodeLength = BARCODE.get(0).length();
            for (final String barcode : BARCODE) {
                if (barcode.length() != barcodeLength) {
                    messages.add("Barcode " + barcode + " has different length from first barcode.");
                }
                if (barcodes.contains(barcode)) {
                    messages.add("Barcode " + barcode + " specified more than once.");
                }
                barcodes.add(barcode);
                final NamedBarcode namedBarcode = new NamedBarcode(barcode);
                namedBarcodes.add(namedBarcode);
            }
        }
        if (namedBarcodes.size() == 0) {
            messages.add("No barcodes have been specified.");
        }
        if (messages.size() == 0) {
            return null;
        }
        return messages.toArray(new String[messages.size()]);
    }

    public static void main(final String[] argv) {
        System.exit(new ExtractIlluminaBarcodes().instanceMain(argv));
    }

    private static final String BARCODE_SEQUENCE_COLUMN = "barcode_sequence";
    private static final String BARCODE_NAME_COLUMN = "barcode_name";
    private static final String LIBRARY_NAME_COLUMN = "library_name";

    private void parseBarcodeFile(final ArrayList<String> messages) {
        final TabbedTextFileWithHeaderParser barcodesParser = new TabbedTextFileWithHeaderParser(BARCODE_FILE);
        if (!barcodesParser.hasColumn(BARCODE_SEQUENCE_COLUMN)) {
            messages.add(BARCODE_FILE + " does not have " + BARCODE_SEQUENCE_COLUMN + " column header");
            return;
        }
        final boolean hasBarcodeName = barcodesParser.hasColumn(BARCODE_NAME_COLUMN);
        final boolean hasLibraryName = barcodesParser.hasColumn(LIBRARY_NAME_COLUMN);

        barcodeLength = 0;
        final Set<String> barcodes = new HashSet<String>();
        for (final TabbedTextFileWithHeaderParser.Row row : barcodesParser) {
            final String barcode = row.getField(BARCODE_SEQUENCE_COLUMN);
            if (barcodeLength == 0) barcodeLength = barcode.length();
            if (barcode.length() != barcodeLength) {
                messages.add("Barcode " + barcode + " has different length from first barcode.");
            }
            if (barcodes.contains(barcode)) {
                messages.add("Barcode " + barcode + " specified more than once in " + BARCODE_FILE);
            }
            barcodes.add(barcode);
            final String barcodeName = (hasBarcodeName? row.getField(BARCODE_NAME_COLUMN): "");
            final String libraryName = (hasLibraryName? row.getField(LIBRARY_NAME_COLUMN): "");
            final NamedBarcode namedBarcode = new NamedBarcode(barcode, barcodeName, libraryName);
            namedBarcodes.add(namedBarcode);
        }
    }

    private static class NamedBarcode {
        public final String barcode;
        public final String barcodeName;
        public final String libraryName;

        public NamedBarcode(final String barcode, final String barcodeName, final String libraryName) {
            this.barcode = barcode;
            this.barcodeName = barcodeName;
            this.libraryName = libraryName;
        }

        public NamedBarcode(final String barcode) {
            this.barcode = barcode;
            this.barcodeName = "";
            this.libraryName = "";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final NamedBarcode that = (NamedBarcode) o;

            if (barcode != null ? !barcode.equals(that.barcode) : that.barcode != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return barcode != null ? barcode.hashCode() : 0;
        }
    }

    /**
     * Metrics produced by the ExtractIlluminaBarcodes program that is used to parse data in
     * the basecalls directory and determine to which barcode each read should be assigned.
     */
    public static class BarcodeMetric extends MetricBase {
        /**
         * The barcode (from the set of expected barcodes) for which the following metrics apply.
         * Note that the "symbolic" barcode of NNNNNN is used to report metrics for all reads that
         * do not match a barcode.
         */
        public String BARCODE;
        public String BARCODE_NAME = "";
        public String LIBRARY_NAME = "";
        /** The total number of reads matching the barcode. */
        public int READS = 0;
        /** The number of PF reads matching this barcode (always less than or equal to READS). */
        public int PF_READS = 0;
        /** The number of all reads matching this barcode that matched with 0 errors or no-calls. */
        public int PERFECT_MATCHES = 0;
        /** The number of PF reads matching this barcode that matched with 0 errors or no-calls. */
        public int PF_PERFECT_MATCHES = 0;
        /** The number of all reads matching this barcode that matched with 1 error or no-call. */
        public int ONE_MISMATCH_MATCHES = 0;
        /** The number of PF reads matching this barcode that matched with 1 error or no-call. */
        public int PF_ONE_MISMATCH_MATCHES = 0;
        /** The percentage of all reads in the lane that matched to this barcode. */
        public double PCT_MATCHES = 0d;
        /**
         * The rate of all reads matching this barcode to all reads matching the most prevelant barcode. For the
         * most prevelant barcode this will be 1, for all others it will be less than 1.  One over the lowest
         * number in this column gives you the fold-difference in representation between barcodes.
         */
        public double RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT = 0d;
        /** The percentage of PF reads in the lane that matched to this barcode. */
        public double PF_PCT_MATCHES = 0d;

        /**
         * The rate of PF reads matching this barcode to PF reads matching the most prevelant barcode. For the
         * most prevelant barcode this will be 1, for all others it will be less than 1.  One over the lowest
         * number in this column gives you the fold-difference in representation of PF reads between barcodes.
         */
        public double PF_RATIO_THIS_BARCODE_TO_BEST_BARCODE_PCT = 0d;

        /**
         * The "normalized" matches to each barcode. This is calculated as the number of pf reads matching
         * this barcode over the sum of all pf reads matching any barcode (excluding orphans). If all barcodes
         * are represented equally this will be 1.
         */
        public double PF_NORMALIZED_MATCHES;

        protected final byte[] barcodeBytes;

        public BarcodeMetric(final NamedBarcode namedBarcode) {
            this.BARCODE = namedBarcode.barcode;
            this.BARCODE_NAME = namedBarcode.barcodeName;
            this.LIBRARY_NAME = namedBarcode.libraryName;
            this.barcodeBytes = net.sf.samtools.util.StringUtil.stringToBytes(this.BARCODE);
        }

        /**
         * This ctor is necessary for when reading metrics from file
         */
        public BarcodeMetric() {
            barcodeBytes = null;
        }
    }
}
