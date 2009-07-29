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

package net.sf.picard.sam;

import net.sf.picard.metrics.MetricBase;
import net.sf.picard.metrics.MetricsFile;
import net.sf.picard.reference.ReferenceSequence;
import net.sf.picard.reference.ReferenceSequenceFile;
import net.sf.picard.util.Histogram;
import net.sf.picard.PicardException;
import net.sf.samtools.*;
import net.sf.samtools.SAMValidationError.Type;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.*;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Tests almost all error conditions detected by the sam file validator. The
 * conditions not tested are proactively prevented by sam generation code.
 *
 * @author Doug Voet
 */
public class ValidateSamFileTest {
    private static final File TEST_DATA_DIR = new File("testdata/net/sf/picard/sam/ValidateSamFileTest");

    @Test
    public void testSortOrder() throws IOException {
        Histogram<Type> results = executeValidation(new SAMFileReader(new File(TEST_DATA_DIR, "invalid_coord_sort_order.sam")), null);
        Assert.assertEquals(results.get(SAMValidationError.Type.RECORD_OUT_OF_ORDER).getValue(), 1.0);
        results = executeValidation(new SAMFileReader(new File(TEST_DATA_DIR, "invalid_queryname_sort_order.sam")), null);
        Assert.assertEquals(results.get(SAMValidationError.Type.RECORD_OUT_OF_ORDER).getValue(), 5.0);
    }
    
    @Test
    public void testVerbose() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<20; i++) {
            samBuilder.addFrag(String.valueOf(i), 1, i, false);
        }
        for (final SAMRecord record : samBuilder) {
            record.setProperPairFlag(true);
        }
        
        final StringWriter results = new StringWriter();
        new SamFileValidator().validateSamFileVerbose(
                samBuilder.getSamReader(), 
                new PrintWriter(results), 
                null, 
                10);
        
        final int lineCount = results.toString().split("\n").length;
        Assert.assertEquals(lineCount, 11);
    }
    
    @Test
    public void testUnpairedRecords() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<6; i++) {
            samBuilder.addFrag(String.valueOf(i), i, i, false);
        }
        final Iterator<SAMRecord> records = samBuilder.iterator();
        records.next().setProperPairFlag(true);
        records.next().setMateUnmappedFlag(true);
        records.next().setMateNegativeStrandFlag(true);
        records.next().setFirstOfPairFlag(true);
        records.next().setSecondOfPairFlag(true);
        records.next().setMateReferenceIndex(1);
        
        final Histogram<Type> results = executeValidation(samBuilder.getSamReader(), null);
        
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_PROPER_PAIR).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_MATE_UNMAPPED).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_MATE_NEG_STRAND).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_FIRST_OF_PAIR).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_SECOND_OF_PAIR).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_MATE_REF_INDEX).getValue(), 1.0);
    }

    @Test
    public void testPairedRecords() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<5; i++) {
            samBuilder.addPair(String.valueOf(i), i, i, i+100);
        }
        final Iterator<SAMRecord> records = samBuilder.iterator();
        records.next().setMateReferenceName("*");
        records.next().setMateAlignmentStart(Integer.MAX_VALUE);
        records.next().setMateAlignmentStart(records.next().getAlignmentStart()+1);
        records.next().setMateNegativeStrandFlag(!records.next().getReadNegativeStrandFlag());
        records.next().setMateReferenceIndex(records.next().getReferenceIndex() + 1);
        records.next().setMateUnmappedFlag(!records.next().getReadUnmappedFlag());

        
        final Histogram<Type> results = executeValidation(samBuilder.getSamReader(), null);
        
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_ALIGNMENT_START).getValue(), 3.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_MATE_UNMAPPED).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.MISMATCH_FLAG_MATE_NEG_STRAND).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.MISMATCH_FLAG_MATE_UNMAPPED).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.MISMATCH_MATE_ALIGNMENT_START).getValue(), 2.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.MISMATCH_MATE_REF_INDEX).getValue(), 2.0);
    }

    @Test
    public void testUnmappedRecords() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<4; i++) {
            samBuilder.addUnmappedFragment(String.valueOf(i));
        }
        final Iterator<SAMRecord> records = samBuilder.iterator();
        records.next().setReadNegativeStrandFlag(true);
        records.next().setNotPrimaryAlignmentFlag(true);
        records.next().setMappingQuality(10);
        records.next().setCigarString("36M");
        
        final Histogram<Type> results = executeValidation(samBuilder.getSamReader(), null);
        
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_READ_NEG_STRAND).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_NOT_PRIM_ALIGNMENT).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_MAPPING_QUALITY).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_CIGAR).getValue(), 1.0);
    }

    @Test
    public void testMappedRecords() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<2; i++) {
            samBuilder.addFrag(String.valueOf(i), i, i, false);
        }
        final Iterator<SAMRecord> records = samBuilder.iterator();
        records.next().setCigarString("");
        records.next().setReferenceName("*");
        
        final Histogram<Type> results = executeValidation(samBuilder.getSamReader(), null);
        
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_CIGAR).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_FLAG_READ_UNMAPPED).getValue(), 1.0);
    }
    
    @Test
    public void testNmFlagValidation() throws IOException {
        final SAMRecordSetBuilder samBuilder = new SAMRecordSetBuilder();
        
        for (int i=0; i<2; i++) {
            samBuilder.addFrag(String.valueOf(i), i, i+1, false);
        }
        final Iterator<SAMRecord> records = samBuilder.iterator();
        records.next().setAttribute(ReservedTagConstants.NM, 4);
        
        final Histogram<Type> results = executeValidation(samBuilder.getSamReader(), new ReferenceSequenceFile() {
            private int index=0;
            public SAMSequenceDictionary getSequenceDictionary() {
                return null;
            }

            public ReferenceSequence nextSequence() {
                final byte[] bases = new byte[10000];
                Arrays.fill(bases, (byte) 'A'); 
                return new ReferenceSequence("foo", index++, bases);
            }
            
        });
        
        Assert.assertEquals(results.get(SAMValidationError.Type.INVALID_TAG_NM).getValue(), 1.0);
        Assert.assertEquals(results.get(SAMValidationError.Type.MISSING_TAG_NM).getValue(), 1.0);
    }

    @Test(dataProvider = "testTruncatedScenarios")
    public void testTruncated(final String scenario, final String inputFile, final SAMValidationError.Type expectedError)
            throws Exception {
        final SAMFileReader reader = new SAMFileReader(new File(TEST_DATA_DIR, inputFile));
        final Histogram<Type> results = executeValidation(reader, null);
        Assert.assertNotNull(results.get(expectedError));
        Assert.assertEquals(results.get(expectedError).getValue(), 1.0);
    }

    @DataProvider(name = "testTruncatedScenarios")
    public Object[][] testTruncatedScenarios() {
        return new Object[][] {
                {"truncated bam", "truncated.bam", SAMValidationError.Type.TRUNCATED_FILE},
                {"truncated quals", "truncated_quals.sam", SAMValidationError.Type.MISMATCH_READ_LENGTH_AND_QUALS_LENGTH},
                // TODO: Because validation is turned off when parsing, this error is not detectable currently by validator.
                //{"truncated tag", "truncated_tag.sam", SAMValidationError.Type.TRUNCATED_FILE},
                // TODO: Currently, this is not considered an error.  Should it be?
                //{"hanging tab", "hanging_tab.sam", SAMValidationError.Type.TRUNCATED_FILE},
        };
    }

    @Test(expectedExceptions = PicardException.class, dataProvider = "testFatalParsingErrors")
    public void testFatalParsingErrors(final String scenario, final String inputFile) throws Exception {
        final SAMFileReader reader = new SAMFileReader(new File(TEST_DATA_DIR, inputFile));
        executeValidation(reader, null);
        Assert.fail("Exception should have been thrown.");
    }

    @DataProvider(name = "testFatalParsingErrors")
    public Object[][] testFatalParsingErrorScenarios() {
        return new Object[][] {
                {"missing fields", "missing_fields.sam"},
                {"zero length read", "zero_length_read.sam"}
        };
    }

    private Histogram<Type> executeValidation(final SAMFileReader samReader, final ReferenceSequenceFile reference) throws IOException {
        final File outFile = File.createTempFile("validation", ".txt");
        final PrintWriter out = new PrintWriter(outFile);
        new SamFileValidator().validateSamFileSummary(samReader, out, reference);
        final MetricsFile<MetricBase, Type> outputFile = new MetricsFile<MetricBase, Type>();
        outputFile.read(new FileReader(outFile));
        Assert.assertNotNull(outputFile.getHistogram());
        return outputFile.getHistogram();
    }
}
