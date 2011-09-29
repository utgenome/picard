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

import org.testng.annotations.Test;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.AfterTest;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.ArrayList;

import net.sf.picard.io.IoUtil;
import net.sf.samtools.util.LineReader;
import net.sf.samtools.util.BufferedLineReader;

/**
 * Run IlluminaBasecallsToSam in various barcode & non-barcode modes
 *
 * @author alecw@broadinstitute.org
 */
public class IlluminaBasecallsToSamTest {

    private static final String RANDOM_SEED_PROPERTY = "picard.random.seed";

    private String picardRandomSeed;
    private static final File BASECALLS_DIR = new File("testdata/net/sf/picard/illumina/IlluminaTests/BasecallsDir");
    private static final File TEST_DATA_DIR = new File("testdata/net/sf/picard/illumina/IlluminaBasecallsToSamTest");

    @BeforeTest
    void beforeTest() {
        // For predictable 2ndary base calling
        picardRandomSeed = System.getProperty(RANDOM_SEED_PROPERTY);
        System.setProperty(RANDOM_SEED_PROPERTY, "0");
    }

    @AfterTest
    void afterTest() {
        if (picardRandomSeed != null) {
            System.setProperty(RANDOM_SEED_PROPERTY, picardRandomSeed);
        } else {
            System.clearProperty(RANDOM_SEED_PROPERTY);
        }
    }

    @Test
    public void testNonBarcoded() throws Exception {
        final File outputBam = File.createTempFile("nonBarcoded.", ".sam");
        outputBam.deleteOnExit();
        final int lane = 1;
        new IlluminaBasecallsToSam().instanceMain(new String[] {
                "BASECALLS_DIR=" + BASECALLS_DIR,
                "LANE=" + lane,
                "OUTPUT=" + outputBam,
                "RUN_BARCODE=HiMom",
                "SAMPLE_ALIAS=HiDad",
                "LIBRARY_NAME=Hello, World"
        });
        IoUtil.assertFilesEqual(outputBam, new File(TEST_DATA_DIR, "nonBarcoded.sam"));
    }

    @Test
    public void testMultiplexed() throws Exception {
        final File outputDir = File.createTempFile("multiplexedBarcode.", ".dir");
        outputDir.delete();
        outputDir.mkdir();
        outputDir.deleteOnExit();
        // Create barcode.params with output files in the temp directory
        final File barcodeParams = new File(outputDir, "barcode.params");
        barcodeParams.deleteOnExit();
        final List<File> samFiles = new ArrayList<File>();
        final LineReader reader = new BufferedLineReader(new FileInputStream(new File(TEST_DATA_DIR, "barcode.params")));
        final PrintWriter writer = new PrintWriter(barcodeParams);
        final String header = reader.readLine();
        writer.println(header + "\tOUTPUT");
        while (true) {
            final String line = reader.readLine();
            if (line == null) {
                break;
            }
            final String[] fields = line.split("\t", 2);
            final File outputSam = new File(outputDir, fields[0] + ".sam");
            outputSam.deleteOnExit();
            samFiles.add(outputSam);
            writer.println(line + "\t" + outputSam);
        }
        writer.close();

        final int lane = 7;
        new IlluminaBasecallsToSam().instanceMain(new String[] {
                "BASECALLS_DIR=" + BASECALLS_DIR,
                "LANE=" + lane,
                "RUN_BARCODE=HiMom",
                "BARCODE_CYCLE=31",
                "BARCODE_LENGTH=8",
                "BARCODE_PARAMS=" + barcodeParams
        });

        for (final File outputSam : samFiles) {
            IoUtil.assertFilesEqual(outputSam, new File(TEST_DATA_DIR, outputSam.getName()));
        }
    }
}
