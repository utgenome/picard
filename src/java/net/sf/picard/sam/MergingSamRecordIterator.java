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

import net.sf.picard.PicardException;

import java.util.*;
import java.lang.reflect.Constructor;

import net.sf.samtools.*;

/**
 * Provides an iterator interface for merging multiple underlying iterators into a single
 * iterable stream. The underlying iterators/files must all have the same sort order unless
 * the requested output format is unsorted, in which case any combination is valid.
 */
public class MergingSamRecordIterator implements Iterator<SAMRecord> {
    private final PriorityQueue<ComparableSamRecordIterator> pq;
    private final SamFileHeaderMerger samHeaderMerger;
    private final SAMFileHeader.SortOrder sortOrder;

    /**
     * Constructs a new merging iterator with the same set of readers and sort order as
     * provided by the header merger parameter.
     */
    public MergingSamRecordIterator(final SamFileHeaderMerger headerMerger, final boolean forcePresorted) {
        this.samHeaderMerger = headerMerger;
        this.sortOrder = headerMerger.getMergedHeader().getSortOrder();
        final SAMRecordComparator comparator = getComparator();

        final Collection<SAMFileReader> readers = headerMerger.getReaders();
        this.pq = new PriorityQueue<ComparableSamRecordIterator>(readers.size());
        
        for (final SAMFileReader reader : readers) {
            if (!forcePresorted && this.sortOrder != SAMFileHeader.SortOrder.unsorted &&
                    reader.getFileHeader().getSortOrder() != this.sortOrder){
                throw new PicardException("Files are not compatible with sort order");   
            }

            final ComparableSamRecordIterator iterator = new ComparableSamRecordIterator(reader, comparator);
            addIfNotEmpty(iterator);
        }
    }

    /** Returns true if any of the underlying iterators has more records, otherwise false. */
    public boolean hasNext() {
        return !this.pq.isEmpty();
    }

    /** Returns the next record from the top most iterator during merging. */
    public SAMRecord next() {;
        final ComparableSamRecordIterator iterator = this.pq.poll();
        final SAMRecord record = iterator.next();
        addIfNotEmpty(iterator);
        record.setHeader(this.samHeaderMerger.getMergedHeader());

        // Fix the read group if needs be
        if (this.samHeaderMerger.hasReadGroupCollisions()) {
            final String oldGroupId = (String) record.getAttribute(ReservedTagConstants.READ_GROUP_ID);
            if (oldGroupId != null ) {
                final String newGroupId = this.samHeaderMerger.getReadGroupId(iterator.getReader(), oldGroupId);
                record.setAttribute(ReservedTagConstants.READ_GROUP_ID, newGroupId); 
                }
        }

        // Fix the program group if needs be
        if (this.samHeaderMerger.hasProgramGroupCollisions()) { 
            final String oldGroupId = (String) record.getAttribute(ReservedTagConstants.PROGRAM_GROUP_ID);
            if (oldGroupId != null ) {
                final String newGroupId = this.samHeaderMerger.getProgramGroupId(iterator.getReader(), oldGroupId);
                record.setAttribute(ReservedTagConstants.PROGRAM_GROUP_ID, newGroupId); 
                }
        }

        // Fix up the sequence indexes if needs be
        if (this.samHeaderMerger.hasMergedSequenceDictionary()) {
            if (record.getReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                record.setReferenceIndex(this.samHeaderMerger.getMergedSequenceIndex(iterator.getReader(),record.getReferenceIndex()));
            }

            if (record.getReadPairedFlag() && record.getMateReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                record.setMateReferenceIndex(this.samHeaderMerger.getMergedSequenceIndex(iterator.getReader(), record.getMateReferenceIndex()));
            }
        }

        return record;
    }

    /**
     * Adds iterator to priority queue. If the iterator has more records it is added
     * otherwise it is closed and not added.
     */
    private void addIfNotEmpty(final ComparableSamRecordIterator iterator) {
        if (iterator.hasNext()) {
            pq.offer(iterator);
        }
        else {
            iterator.close();
        }
    }

    /** Unsupported operation. */
    public void remove() {
        throw new UnsupportedOperationException("MergingSAMRecorderIterator.remove()");
    }

    /**
     * Get the right comparator for a given sort order (coordinate, alphabetic). In the
     * case of "unsorted" it will return a comparator that gives an arbitrary but reflexive
     * ordering.
     */
    private SAMRecordComparator getComparator() {
        // For unsorted build a fake comparator that compares based on object ID
        if (this.sortOrder == SAMFileHeader.SortOrder.unsorted) {
            return new SAMRecordComparator() {
                public int fileOrderCompare(final SAMRecord lhs, final SAMRecord rhs) {
                    return System.identityHashCode(lhs) - System.identityHashCode(rhs);
                }

                public int compare(final SAMRecord lhs, final SAMRecord rhs) {
                    return fileOrderCompare(lhs, rhs);
                }
            };
        }
        if (samHeaderMerger.hasMergedSequenceDictionary() && sortOrder.equals(SAMFileHeader.SortOrder.coordinate)) {
            return new MergedSequenceDictionaryCoordinateOrderComparator();
        }

        // Otherwise try and figure out what kind of comparator to return and build it
        final Class<? extends SAMRecordComparator> type = this.sortOrder.getComparator();

        try {
            final Constructor<? extends SAMRecordComparator> ctor = type.getConstructor();
            return ctor.newInstance();
        }
        catch (Exception e) {
            throw new PicardException("Could not instantiate a comparator for sort order: " + this.sortOrder, e);
        }
    }

    /** Returns the merged header that the merging iterator is working from. */
    public SAMFileHeader getMergedHeader() {
        return this.samHeaderMerger.getMergedHeader();
    }

    /**
     * Ugh.  Basically does a regular coordinate compare, but looks up the sequence indices in the merged
     * sequence dictionary.  I hate the fact that this extends SAMRecordCoordinateComparator, but it avoids
     * more copy & paste.
     */
    private class MergedSequenceDictionaryCoordinateOrderComparator extends SAMRecordCoordinateComparator {

        public int fileOrderCompare(final SAMRecord samRecord1, final SAMRecord samRecord2) {
            final int referenceIndex1 = getReferenceIndex(samRecord1);
            final int referenceIndex2 = getReferenceIndex(samRecord2);
            if (referenceIndex1 != referenceIndex2) {
                if (referenceIndex1 == SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                    return 1;
                } else if (referenceIndex2 == SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                    return -1;
                } else {
                    return referenceIndex1 - referenceIndex2;
                }
            }
            if (referenceIndex1 == SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                // Both are unmapped.
                return 0;
            }
            return samRecord1.getAlignmentStart() - samRecord2.getAlignmentStart();
        }

        private int getReferenceIndex(final SAMRecord samRecord) {
            if (samRecord.getReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                return samHeaderMerger.getMergedSequenceIndex(samRecord.getHeader(), samRecord.getReferenceIndex());
            }
            if (samRecord.getMateReferenceIndex() != SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX) {
                return samHeaderMerger.getMergedSequenceIndex(samRecord.getHeader(), samRecord.getMateReferenceIndex());
            }
            return SAMRecord.NO_ALIGNMENT_REFERENCE_INDEX;
        }
    }
}
