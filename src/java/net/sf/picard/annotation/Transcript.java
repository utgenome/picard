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
package net.sf.picard.annotation;

import net.sf.samtools.util.CoordMath;

import java.util.Collections;
import java.util.List;

/**
 * A single transcript of a gene.
 */
public class Transcript {
    public final String name;
    public final int transcriptionStart;
    public final int transcriptionEnd;
    public final int codingStart;
    public final int codingEnd;
    public final Exon[] exons;

    public Transcript(String name, int transcriptionStart, int transcriptionEnd, int codingStart, int codingEnd, Exon[] exons) {
        this.name = name;
        this.transcriptionStart = transcriptionStart;
        this.transcriptionEnd = transcriptionEnd;
        this.codingStart = codingStart;
        this.codingEnd = codingEnd;
        this.exons = exons;
    }

    public int start() {
        return exons[0].start;
    }

    public int end() {
        return exons[exons.length -1].end;
    }

    /**
     * Write into locusFunctions the function of each position from start to start + locusFunctions.length
     * relative to this transcript.  Does not overwrite an existing value in locusFunctions that is stronger
     * than the function for that locus in this transcript.
     * @param start
     * @param locusFunctions
     */
    public void getLocusFunctionForRange(int start, LocusFunction[] locusFunctions) {
        for (int i = Math.max(start, transcriptionStart);
                i <= Math.min(transcriptionEnd, CoordMath.getEnd(start, locusFunctions.length)); ++i) {
            final LocusFunction locusFunction;
            if (inExon(i)) {
                if (utr(i)) locusFunction = LocusFunction.UTR;
                else locusFunction = LocusFunction.CODING;
            } else locusFunction = LocusFunction.INTRONIC;
            if (locusFunction.ordinal() > locusFunctions[i - start].ordinal()) {
                locusFunctions[i - start] = locusFunction;
            }
        }
    }

    private boolean utr(int locus) {
        return locus < codingStart || locus > codingEnd;
    }

    private boolean inExon(int locus) {
        for (int i = 0; i < exons.length; ++i) {
            final Exon exon = exons[i];
            if (exon.start > locus) return false;
            if (inRange(exon.start, exon.end, locus)) return true;
        }
        return false;
    }



    private boolean inRange(int start, int end, int locus) {
        return (locus >= start && locus <= end);
    }
}
