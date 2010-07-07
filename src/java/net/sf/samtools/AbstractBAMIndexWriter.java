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

import java.io.File;

/**
 * A basic interface for writing BAM index files
 *
 * @author mborkan
 */
public abstract class AbstractBAMIndexWriter implements BAMIndexWriter {

    protected final int n_ref;
    protected final File output;

    /**
     * Constructor
     *
     * @param output      BAM Index (.bai) file (or bai.txt file when text)
     * @param nReferences Number of references in the input BAM file
     */
    public AbstractBAMIndexWriter(final File output, final int nReferences) {
        this.output = output;
        this.n_ref = nReferences;
    }


    abstract public void writeHeader();

    abstract public void writeReference(final BAMIndexContent content, int reference);

    abstract public void close();

    /**
     * Deletes old or partial index file
     * Called whenever exceptions occur.
     */
    public void deleteIndexFile() {
        output.delete();
    }
}