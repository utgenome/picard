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

import net.sf.picard.illumina.parser.readers.BclQualityEvaluationStrategy;
import net.sf.picard.util.BasicInputParser;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.AfterTest;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.testng.Assert;

import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import net.sf.picard.io.IoUtil;
import net.sf.picard.metrics.MetricsFile;
import net.sf.picard.illumina.parser.*;

/**
 * @author alecw@broadinstitute.org
 */
public class ExtractIlluminaBarcodesTest {
    private static final File TEST_DATA_DIR = new File("testdata/net/sf/picard/illumina/ExtractIlluminaBarcodes");
    private static final String[] BARCODES = {
            "ACAGTG",
            "ACAGTT", // This one is artificial -- one edit away from the first one
            "ACTTGA",
            "ATCACG",
            "CAGATC",
            "CGATGT",
            "CTTGTA",
            "GATCAG",
            "GCCAAT",
            "TAGCTT",
            "TGACCA"
    };

    private File basecallsDir;
    private File dual;
    private File qual;

    @BeforeTest
    private void setUp() throws Exception {
        basecallsDir = File.createTempFile("eib.", ".tmp");
        Assert.assertTrue(basecallsDir.delete());
        Assert.assertTrue(basecallsDir.mkdir());
        for (final File source : TEST_DATA_DIR.listFiles()) {
            if (!source.isFile()) {
                continue;
            }
            final File dest = new File(basecallsDir, source.getName());
            IoUtil.copyFile(source, dest);
        }
        dual = File.createTempFile("eib_dual", ".tmp");
        Assert.assertTrue(dual.delete());
        Assert.assertTrue(dual.mkdir());
        for (final File source : new File(TEST_DATA_DIR, "dual").listFiles()) {
            if (!source.isFile()) {
                continue;
            }
            final File dest = new File(dual, source.getName());
            IoUtil.copyFile(source, dest);
        }
        qual = File.createTempFile("eib_qual", ".tmp");
        Assert.assertTrue(qual.delete());
        Assert.assertTrue(qual.mkdir());
        for (final File source : new File(TEST_DATA_DIR, "qual").listFiles()) {
            if (!source.isFile()) {
                continue;
            }
            final File dest = new File(qual, source.getName());
            IoUtil.copyFile(source, dest);
        }
    }

    @AfterTest
    private void tearDown() {
        IoUtil.deleteDirectoryTree(basecallsDir);
        IoUtil.deleteDirectoryTree(dual);
        IoUtil.deleteDirectoryTree(qual);
    }

    @Test
    public void testSingleEndWithBarcodeAtStart() throws Exception {
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metricsFile = runIt(1, "6B36T");
        Assert.assertEquals(metricsFile.getMetrics().get(0).PERFECT_MATCHES, 1);
    }

    @Test
    public void testSingleEndWithBarcodeAtEnd() throws Exception {
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metricsFile = runIt(2, "36T6B");
        Assert.assertEquals(metricsFile.getMetrics().get(0).PERFECT_MATCHES, 1);
    }

    @Test
    public void testPairedEndWithBarcodeOnFirstEnd() throws Exception {
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metricsFile = runIt(3, "36T6B36T");
        Assert.assertEquals(metricsFile.getMetrics().get(0).PERFECT_MATCHES, 1);
    }

    @Test
    public void testPairedEndWithBarcodeOnSecondEnd() throws Exception {
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metricsFile = runIt(4, "36T36T6B");
        Assert.assertEquals(metricsFile.getMetrics().get(0).PERFECT_MATCHES, 1);
    }

    @Test
    public void testNonWritableOutputFile() throws Exception {
        final File existingFile = new File(basecallsDir, "s_1_0001_barcode.txt.gz");
        existingFile.createNewFile();
        existingFile.setReadOnly();
        final String readStructure = "6B36T";
        final int lane = 1;

        final File metricsFile = File.createTempFile("eib.", ".metrics");
        metricsFile.deleteOnExit();

        final List<String> args = new ArrayList<String>(Arrays.asList(
                "BASECALLS_DIR=" + basecallsDir.getPath(),
                "LANE=" + lane,
                "READ_STRUCTURE=" + readStructure,
                "METRICS_FILE=" + metricsFile.getPath(),
                "COMPRESS_OUTPUTS=true"
        ));
        for (final String barcode : BARCODES) {
            args.add("BARCODE=" + barcode);
        }
        Assert.assertEquals(new ExtractIlluminaBarcodes().instanceMain(args.toArray(new String[args.size()])), 4);
    }

    /**
     * 4 cases tested:
     * * exact match to ACAGTG
     * * inexact match within threshold to TGACCA
     * * inexact match not within threshold to TGACCA
     * * inexact match where the next match is too close to ACAGTG
     * @throws Exception
     */
    @Test
    public void testBarcodeMatching() throws Exception {
        final int lane = 5;
        final int barcodePosition = 37;
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> metricsFile = runIt(lane, "36T6B");

        ExtractIlluminaBarcodes.BarcodeMetric metricACAGTG = null;
        ExtractIlluminaBarcodes.BarcodeMetric metricTGACCA = null;
        ExtractIlluminaBarcodes.BarcodeMetric metricNoMatch = null;
        for (final ExtractIlluminaBarcodes.BarcodeMetric metric : metricsFile.getMetrics()) {
            if (metric.BARCODE.equals("ACAGTG")) {
                metricACAGTG = metric;
            } else if (metric.BARCODE.equals("TGACCA")) {
                metricTGACCA = metric;
            } else if (metric.BARCODE.equals("NNNNNN")) {
                metricNoMatch = metric;
            }
        }
        Assert.assertEquals(metricACAGTG.PERFECT_MATCHES, 1);
        Assert.assertEquals(metricACAGTG.ONE_MISMATCH_MATCHES, 0);
        Assert.assertEquals(metricACAGTG.PF_READS, 1);
        Assert.assertEquals(metricACAGTG.READS, 1);

        for (final ExtractIlluminaBarcodes.BarcodeMetric metric : metricsFile.getMetrics()) {
            if (metric == metricACAGTG || metric == metricTGACCA || metric == metricNoMatch) {
                continue;
            }
            Assert.assertEquals(metric.READS, 0);
        }

        // one inexact match
        Assert.assertEquals(metricTGACCA.READS, 1);
        Assert.assertEquals(metricTGACCA.ONE_MISMATCH_MATCHES, 1);

        Assert.assertEquals(metricNoMatch.READS, 2);
        Assert.assertEquals(metricNoMatch.PF_READS, 1);

        // Check the barcode files themselves
        final File[] barcodeFiles = IoUtil.getFilesMatchingRegexp(basecallsDir, "s_" + lane + "_\\d{4}_barcode.txt");
        Arrays.sort(barcodeFiles);

        final BasicInputParser barcodeParser = new BasicInputParser(true, barcodeFiles);

        // Exact match
        String[] illuminaFields = barcodeParser.next();
        Assert.assertEquals(illuminaFields[1], "Y");
        Assert.assertEquals(illuminaFields[2], "ACAGTG");

        // Inexact match
        illuminaFields = barcodeParser.next();
        Assert.assertEquals(illuminaFields[1], "Y");
        Assert.assertEquals(illuminaFields[2], "TGACCA");

        // Too many mismatches
        illuminaFields = barcodeParser.next();
        Assert.assertEquals(illuminaFields[1], "N");

        // Next match too close
        illuminaFields = barcodeParser.next();
        Assert.assertEquals(illuminaFields[1], "N");

        Assert.assertFalse(barcodeParser.hasNext());
        barcodeParser.close();

        // Tack on test of barcode-informed Illumina Basecall parsing
        final ReadStructure rs = new ReadStructure("36T6B");
        final IlluminaDataProviderFactory factory = new IlluminaDataProviderFactory(basecallsDir, lane, rs, 
                new BclQualityEvaluationStrategy(BclQualityEvaluationStrategy.ILLUMINA_ALLEGED_MINIMUM_QUALITY),
                IlluminaDataType.BaseCalls, IlluminaDataType.QualityScores, IlluminaDataType.Barcodes);
        testParsing(factory, rs, metricACAGTG, barcodePosition);
    }

    @Test(dataProvider = "dualBarcodeData")
    public void testDualBarcodes(final int lane, final String readStructure, final int perfectMatches, final int oneMismatchMatches,
                                 final String testName) throws Exception {
        final File metricsFile = File.createTempFile("dual.", ".metrics");
        metricsFile.deleteOnExit();

        final List<String> args = new ArrayList<String>(Arrays.asList(
                "BASECALLS_DIR=" + dual.getAbsolutePath(),
                "LANE=" + lane,
                "BARCODE_FILE=" + new File(dual, "barcodeData." + lane).getAbsolutePath(),
                "METRICS_FILE=" + metricsFile.getPath(),
                "READ_STRUCTURE=" + readStructure
                ));

        Assert.assertEquals(new ExtractIlluminaBarcodes().instanceMain(args.toArray(new String[args.size()])), 0);
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer> result =  new MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer>();
        result.read(new FileReader(metricsFile));
        Assert.assertEquals(result.getMetrics().get(0).PERFECT_MATCHES, perfectMatches, "Got wrong number of perfect matches");
        Assert.assertEquals(result.getMetrics().get(0).ONE_MISMATCH_MATCHES, oneMismatchMatches, "Got wrong number of one-mismatch matches");
    }

    @DataProvider(name = "dualBarcodeData")
    public Object[][] getDualBarcodeTestData() {
        return new Object[][] {
                {4, "10T8B6B10T", 3, 2, "Two barcodes in the middle, but one read is shorter than the barcode in the file"},
                {5, "10T8B6B2S10T", 3, 2, "Two barcodes in the middle, but one is shorter than the read lengths"},
                {6, "10T8B8B10T", 1, 2, "Two barcodes in the middle"},
                {7, "8B10T10T8B", 1, 2, "Two barcodes on either end"},
                {8, "4B10T4B4B10T4B", 1, 2, "Four crazy barcodes, one on either end and two in the middle"}
        };
    }

    /**
     *  Testing the quality thresholding. Looking at a single barcode (ACAGTG) with a min quality of 25 and no mismatches
     */
    @Test(dataProvider = "qualityBarcodeData")
    public void testQualityBarcodes(final int lane, final String readStructure, final String barcode, final int quality,
                                    final int maxMismatches, final int perfectMatches, final int oneMismatch,
                                    final String testName) throws Exception {
        final File metricsFile = File.createTempFile("qual.", ".metrics");
        metricsFile.deleteOnExit();

        final List<String> args = new ArrayList<String>(Arrays.asList(
                "BASECALLS_DIR=" + qual.getPath(),
                "LANE=" + lane,
                "READ_STRUCTURE=" + readStructure,
                "METRICS_FILE=" + metricsFile.getPath(),
                "MINIMUM_BASE_QUALITY=" + quality,
                "MAX_MISMATCHES=" + maxMismatches,
                "BARCODE=" + barcode
        ));

        Assert.assertEquals(new ExtractIlluminaBarcodes().instanceMain(args.toArray(new String[args.size()])), 0);
        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer> result =  new MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer>();
        result.read(new FileReader(metricsFile));
        Assert.assertEquals(result.getMetrics().get(0).PERFECT_MATCHES, perfectMatches, "Got wrong number of perfect matches for test: '" + testName + "'");
        Assert.assertEquals(result.getMetrics().get(0).ONE_MISMATCH_MATCHES, oneMismatch, "Got wrong number of one-mismatch matches for test: '" + testName + "'");
    }

    @DataProvider(name = "qualityBarcodeData")
    public Object[][] getQualityTestData() {
        return new Object[][] {
                {1, "6B36T", "ACAGTG", 25, 0, 1, 0, "Barcode has good quality, 1 match"},
                {2, "6B36T", "ACAGTG", 25, 0, 0, 0, "Barcode has quality failures, no matches"},
                {3, "6B36T", "ACAGTG", 25, 0, 0, 0, "Barcode has one low quality, no matches"},
                {4, "36T6B", "ACAGTG", 25, 0, 0, 0, "Barcode at end, quality failures, no matches"},
                {5, "6B36T", "ACAGTG", 25, 1, 0, 1, "Barcode has 1 low quality, 1 mismatch allowed, 1 match"}
        };
    }

    private void testParsing(final IlluminaDataProviderFactory factory, final ReadStructure readStructure, final ExtractIlluminaBarcodes.BarcodeMetric metricACAGTG, final int barcodePosition) {

        int numReads = 0;

        final IlluminaDataProvider dataProvider = factory.makeDataProvider();
        while (dataProvider.hasNext()) {
            final ClusterData cluster = dataProvider.next();

            if(metricACAGTG.BARCODE.equals(cluster.getMatchedBarcode())) {
                ++numReads;
            }

            Assert.assertEquals(cluster.getRead(readStructure.templates.getIndices()[0]).getQualities().length, barcodePosition - 1);
            Assert.assertEquals(cluster.getRead(readStructure.templates.getIndices()[0]).getBases().length, barcodePosition - 1);
        }
        Assert.assertEquals(numReads, metricACAGTG.READS);
    }

    private MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> runIt(final int lane, final String readStructure)
            throws Exception {
        final File metricsFile = File.createTempFile("eib.", ".metrics");
        metricsFile.deleteOnExit();

        final List<String> args = new ArrayList<String>(Arrays.asList(
                "BASECALLS_DIR=" + basecallsDir.getPath(),
                "LANE=" + lane,
                "READ_STRUCTURE=" + readStructure,
                "METRICS_FILE=" + metricsFile.getPath()
        ));
        for (final String barcode : BARCODES) {
            args.add("BARCODE=" + barcode);
        }
        return runIt(args, metricsFile);
    }

    private MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric, Integer> runIt(final List<String> args, final File metricsFile) throws Exception {

        // Generate _barcode.txt files and metrics file.
        Assert.assertEquals(new ExtractIlluminaBarcodes().instanceMain(args.toArray(new String[args.size()])), 0);

        final MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer> retval =  new MetricsFile<ExtractIlluminaBarcodes.BarcodeMetric,Integer>();
        retval.read(new FileReader(metricsFile));
        return retval;
    }
}
