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
package net.sf.samtools;

import net.sf.picard.util.SequenceUtil;

/**
 * @author alecw@broadinstitute.org
 */
public class SAMRecordUtil {
    /** Returns the complement of a single byte. */
    public static byte complement(final byte b) {
        switch (b) {
            case SequenceUtil.a: return SequenceUtil.t;
            case SequenceUtil.c: return SequenceUtil.g;
            case SequenceUtil.g: return SequenceUtil.c;
            case SequenceUtil.t: return SequenceUtil.a;
            case SequenceUtil.A: return SequenceUtil.T;
            case SequenceUtil.C: return SequenceUtil.G;
            case SequenceUtil.G: return SequenceUtil.C;
            case SequenceUtil.T: return SequenceUtil.A;
            default: return b;
        }
    }

    /** Reverses and complements the bases in place. */
    public static void reverseComplement(final byte[] bases) {
        final int lastIndex = bases.length - 1;

        int i, j;
        for (i=0, j=lastIndex; i<j; ++i, --j) {
            final byte tmp = complement(bases[i]);
            bases[i] = complement(bases[j]);
            bases[j] = tmp;
        }
        if (bases.length % 2 == 1) {
            bases[i] = complement(bases[i]);
        }
    }

    /**
     * Reverse-complement all attributes of the SAMRecord that are known to be reversible.
     */
    public static void reverseComplement(final SAMRecord rec) {
        reverseComplement(rec.getReadBases());
        final byte qualities[] = rec.getBaseQualities();
        reverseArray(qualities);
        rec.setBaseQualities(qualities);
        final byte[] sqTagValue = (byte[])rec.getAttribute(SAMTagUtil.getSingleton().SQ);
        if (sqTagValue != null) {
            SQTagUtil.reverseComplementSqArray(sqTagValue);
            rec.setAttribute(SAMTagUtil.getSingleton().SQ, sqTagValue);
        }
    }

    /**
     * Reverse the given array in place.
     */
    public static void reverseArray(final byte[] array) {
        final int lastIndex = array.length - 1;
        int i, j;
        for (i=0, j=lastIndex; i<j; ++i, --j) {
            final byte tmp = array[i];
            array[i] = array[j];
            array[j] = tmp;
        }
    }
}
