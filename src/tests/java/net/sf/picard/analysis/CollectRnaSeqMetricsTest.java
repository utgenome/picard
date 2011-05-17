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
package net.sf.picard.analysis;

import net.sf.picard.annotation.RefFlatReader.RefFlatColumns;
import net.sf.picard.metrics.MetricsFile;
import net.sf.picard.util.Interval;
import net.sf.picard.util.IntervalList;
import net.sf.samtools.*;
import net.sf.samtools.util.StringUtil;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;

public class CollectRnaSeqMetricsTest {
    @Test
    public void basic() throws Exception {
        final String sequence = "chr1";
        final SAMFileHeader header = new SAMFileHeader();
        header.addSequence(new SAMSequenceRecord(sequence, 1000000));
        final Interval rRnaInterval = new Interval(sequence, 1, 100, true, "rRNA");
        final IntervalList rRnaIntervalList = new IntervalList(header);
        rRnaIntervalList.add(rRnaInterval);
        final File rRnaIntervalsFile = File.createTempFile("tmp.rRna.", ".interval_list");
        rRnaIntervalsFile.deleteOnExit();
        rRnaIntervalList.write(rRnaIntervalsFile);

        final String[] refFlatFields = new String[RefFlatColumns.values().length];
        refFlatFields[RefFlatColumns.GENE_NAME.ordinal()] = "myGene";
        refFlatFields[RefFlatColumns.TRANSCRIPT_NAME.ordinal()] = "myTranscript";
        refFlatFields[RefFlatColumns.CHROMOSOME.ordinal()] = sequence;
        refFlatFields[RefFlatColumns.STRAND.ordinal()] = "+";
        refFlatFields[RefFlatColumns.TX_START.ordinal()] = "50";
        refFlatFields[RefFlatColumns.TX_END.ordinal()] = "500";
        refFlatFields[RefFlatColumns.CDS_START.ordinal()] = "75";
        refFlatFields[RefFlatColumns.CDS_END.ordinal()] = "400";
        refFlatFields[RefFlatColumns.EXON_COUNT.ordinal()] = "2";
        refFlatFields[RefFlatColumns.EXON_STARTS.ordinal()] = "50,250";
        refFlatFields[RefFlatColumns.EXON_ENDS.ordinal()] = "200,500";

        final File refFlatFile = File.createTempFile("tmp.", ".refFlat");
        refFlatFile.deleteOnExit();
        final PrintStream refFlatStream = new PrintStream(refFlatFile);
        refFlatStream.println(StringUtil.join("\t", refFlatFields));
        refFlatStream.close();

        final SAMRecordSetBuilder builder = new SAMRecordSetBuilder(true, SAMFileHeader.SortOrder.coordinate);
        // Set seed so that strandedness is consistent among runs.
        builder.setRandomSeed(0);
        final int sequenceIndex = builder.getHeader().getSequenceIndex(sequence);
        builder.addPair("pair1", sequenceIndex, 45, 475);
        builder.addPair("pair2", sequenceIndex, 90, 225);
        builder.addPair("pair3", sequenceIndex, 120, 600);
        builder.addFrag("frag1", sequenceIndex, 150, true);
        builder.addFrag("frag2", sequenceIndex, 450, true);
        builder.addFrag("frag3", sequenceIndex, 225, false);

        final File samFile = File.createTempFile("tmp.collectRnaSeqMetrics.", ".sam");
        final SAMFileWriter samWriter = new SAMFileWriterFactory().makeSAMWriter(builder.getHeader(), false, samFile);
        for (final SAMRecord rec: builder.getRecords()) samWriter.addAlignment(rec);
        samWriter.close();

        final File metricsFile = File.createTempFile("tmp.", ".rna_metrics");

        final int ret = new CollectRnaSeqMetrics().instanceMain(new String[]{
                "INPUT=" +               samFile.getAbsolutePath(),
                "OUTPUT=" +              metricsFile.getAbsolutePath(),
                "REF_FLAT=" +            refFlatFile.getAbsolutePath(),
                "RIBOSOMAL_INTERVALS=" + rRnaIntervalsFile.getAbsolutePath(),
                "STRAND_SPECIFICITY=SECOND_READ_TRANSCRIPTION_STRAND"
        });
        Assert.assertEquals(ret, 0);

        final MetricsFile<RnaSeqMetrics, Comparable<?>> output = new MetricsFile<RnaSeqMetrics, Comparable<?>>();
        output.read(new FileReader(metricsFile));

        final RnaSeqMetrics metrics = output.getMetrics().get(0);
        Assert.assertEquals(metrics.ALIGNED_PF_BASES, 324);
        Assert.assertEquals(metrics.RIBOSOMAL_BASES, 47);
        Assert.assertEquals(metrics.CODING_BASES, 119);
        Assert.assertEquals(metrics.UTR_BASES, 62);
        Assert.assertEquals(metrics.INTRONIC_BASES, 50);
        Assert.assertEquals(metrics.INTERGENIC_BASES, 46);
        Assert.assertEquals(metrics.CORRECT_STRAND_READS, 4);
        Assert.assertEquals(metrics.INCORRECT_STRAND_READS, 3);
    }
}
