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
package net.sf.samtools.util;

import java.io.*;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;

/**
 * Accumulate a list of longs that can then be sorted in natural order and iterated over.
 * If there are more values accumulated than a specified maximum, values are spilled to disk.
 *
 * Note that because this class returns primitive longs rather than Longs, it does not conform to
 * any of the Collection iteration interfaces.  Use as follows:
 *
 * 1. ctor
 * 2. call add() as many times as desired.
 * 3. call doneAddingStartIteration().
 * 4. call hasNext() and next() until exhausted or had enough.
 * 5. optionally call cleanup() to free space in temporary directory as soon as possible.
 *
 * If there are few enough values so that they all can be kept in RAM, then the array is sorted
 * and iterated over trivially.
 *
 * If there are more values that can fit in RAM, then values are sorted and written to a temp file when the max
 * number to be stored in RAM is reached. Multiple temp files are then merged during iteration via PriorityQueue.
 *
 * c.f. SortingCollection for more details.
 *
 * @author alecw@broadinstitute.org
 */
public class SortingLongCollection {
    public static final int SIZEOF = 8;

    /**
     * Where files of sorted values go.
     */
    private final File tmpDir;

    private final int maxValuesInRam;
    private int numValuesInRam = 0;
    private long[] ramValues;


    /**
     * Set to true when done adding and ready to iterate
     */
    private boolean doneAdding = false;

    /**
     * Set to true when all temp files have been cleaned up
     */
    private boolean cleanedUp = false;

    /**
     * List of files in tmpDir containing sorted values
     */
    private final List<File> files = new ArrayList<File>();

    // for in-memory iteration
    private int iterationIndex = 0;

    // For disk-based iteration
    private PriorityQueue<PeekFileValueIterator> priorityQueue;

    /**
     * Prepare to accumulate values to be sorted
     * @param maxValuesInRam how many values to accumulate before spilling to disk
     * @param tmpDir Where to write files of values that will not fit in RAM
     */
    public SortingLongCollection(final int maxValuesInRam, final File tmpDir) {
        if (maxValuesInRam <= 0) {
            throw new IllegalArgumentException("maxValuesInRam must be > 0");
        }
        this.tmpDir = tmpDir;
        this.maxValuesInRam = maxValuesInRam;
        this.ramValues = new long[maxValuesInRam];
    }

    /**
     * Add a value to the collection.
     * @param value
     */
    public void add(final long value) {
        if (doneAdding) {
            throw new IllegalStateException("Cannot add after calling doneAddingStartIteration()");
        }
        if (numValuesInRam == maxValuesInRam) {
            spillToDisk();
        }
        ramValues[numValuesInRam++] = value;
    }

    /**
     * This method must be called after done adding, and before calling hasNext() or next().
     */
    public void doneAddingStartIteration() {
        if (cleanedUp || doneAdding) {
            throw new IllegalStateException("Cannot call doneAddingStartIteration() after cleanup() was called.");
        }
        doneAdding = true;

        if (this.files.isEmpty()) {
            Arrays.sort(this.ramValues, 0, this.numValuesInRam);
            return;
        }

        if (this.numValuesInRam > 0) {
            spillToDisk();
        }

        this.priorityQueue = new PriorityQueue<PeekFileValueIterator>(files.size(),
                                                                       new PeekFileValueIteratorComparator());
        for (final File f : files) {
            final FileValueIterator it = new FileValueIterator(f);
            if (it.hasNext()) {
                this.priorityQueue.offer(new PeekFileValueIterator(it));
            }
        }

        // Facilitate GC
        this.ramValues = null;
    }

    /**
     * Sort the values in memory, write them to a file, and clear the buffer of values in memory.
     */
    private void spillToDisk() {
        try {
            Arrays.sort(this.ramValues, 0, this.numValuesInRam);
            final File f = File.createTempFile("sortingcollection.", ".tmp", this.tmpDir);
            RandomAccessFile os = null;
            try {
                final long numBytes = this.numValuesInRam * SIZEOF;
                os = new RandomAccessFile(f, "rw");
                f.deleteOnExit();
                final FileChannel channel = os.getChannel();
                final MappedByteBuffer byteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, numBytes);
                final LongBuffer longBuffer = byteBuffer.asLongBuffer();
                longBuffer.put(this.ramValues, 0, this.numValuesInRam);
                byteBuffer.force();
                channel.close();
            }
            finally {
                if (os != null) {
                    os.close();
                }
            }

            this.numValuesInRam = 0;
            this.files.add(f);

        }
        catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Delete any temporary files.  After this method is called, no other method calls should be made on this object.
     */
    public void cleanup() {
        this.doneAdding = true;
        this.cleanedUp = true;
        this.ramValues = null;

        for (final File f : this.files) {
            f.delete();
        }
    }


    /**
     * Call only after doneAddingStartIteration() has been called.
     *
     * @return true if there is another value to be gotten.
     */
    public boolean hasNext() {
        if (!doneAdding || cleanedUp) {
            throw new IllegalStateException();
        }
        if (this.ramValues != null) {
            // in-memory iteration
            return this.iterationIndex < numValuesInRam;
        } else {
            return !priorityQueue.isEmpty();
        }
    }

    /**
     * Call only if hasNext() == true.
     * @return next value from collection, in natural sort order.
     */
    public long next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        if (this.ramValues != null) {
            // in-memory iteration
            return ramValues[iterationIndex++];
        } else {

            final PeekFileValueIterator fileIterator = priorityQueue.poll();
            final long ret = fileIterator.next();
            if (fileIterator.hasNext()) {
                this.priorityQueue.offer(fileIterator);
            } else {
                fileIterator.close();
            }
            return ret;
        }
    }

    /**
     * Read a file of longs
     */
    private static class FileValueIterator {
        private final File file;
        private LongBuffer longBuffer;

        FileValueIterator(final File file) {
            this.file = file;
            try {
                final FileInputStream is = new FileInputStream(file);
                final FileChannel channel = is.getChannel();
                longBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).asLongBuffer();
                // Mapping remains in place despite closing of FileChannel or FileInputStream
                channel.close();
                is.close();
            }
            catch (FileNotFoundException e) {
                throw new RuntimeIOException(file.getAbsolutePath(), e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        boolean hasNext() {
            return longBuffer.hasRemaining();
        }

        long next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return longBuffer.get();
        }

        void close() {
            file.delete();
            longBuffer = null;
        }
    }


    /**
     * Add peek() functionality to FileValueIterator
     */
    private static class PeekFileValueIterator {
        private FileValueIterator underlyingIterator;
        private long peekValue;
        private boolean hasPeekedValue = false;

        PeekFileValueIterator(final FileValueIterator underlyingIterator) {
            this.underlyingIterator = underlyingIterator;
        }

        boolean hasNext() {
            return hasPeekedValue || underlyingIterator.hasNext();
        }

        long next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (hasPeekedValue) {
                hasPeekedValue = false;
                return peekValue;
            }
            return underlyingIterator.next();
        }

        long peek() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            if (!hasPeekedValue) {
                peekValue = underlyingIterator.next();
                hasPeekedValue = true;
            }
            return peekValue;
        }

        void close() {
            underlyingIterator.close();
            hasPeekedValue = false;
            underlyingIterator = null;
        }
    }

    private static class PeekFileValueIteratorComparator implements Comparator<PeekFileValueIterator> {

        public int compare(final PeekFileValueIterator it1, final PeekFileValueIterator it2) {
            if (it1.peek() < it2.peek()) {
                return -1;
            }
            if (it1.peek() == it2.peek()) {
                return 0;
            }
            return 1;
        }
    }
}
