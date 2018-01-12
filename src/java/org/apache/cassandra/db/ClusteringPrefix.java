/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import com.google.common.hash.Hasher;

import org.apache.cassandra.cache.IMeasurableMemory;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredSerializer;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.ByteSource;

import static org.apache.cassandra.utils.ByteBufferUtil.EMPTY_BUFFER_ARRAY;

/**
 * A clustering prefix is the unit of what a {@link ClusteringComparator} can compare.
 * <p>
 * It holds values for the clustering columns of a table (potentially only a prefix of all of them) and has
 * a "kind" that allows us to implement slices with inclusive and exclusive bounds.
 * <p>
 * In practice, {@code ClusteringPrefix} is just the common parts to its 3 main subtype: {@link Clustering} and
 * {@link ClusteringBound}/{@link ClusteringBoundary}, where:
 *   1) {@code Clustering} represents the clustering values for a row, i.e. the values for it's clustering columns.
 *   2) {@code ClusteringBound} represents a bound (start or end) of a slice (of rows) or a range tombstone.
 *   3) {@code ClusteringBoundary} represents the threshold between two adjacent range tombstones.
 * See those classes for more details.
 */
public interface ClusteringPrefix extends IMeasurableMemory, Clusterable
{
    public static final Serializer serializer = new Serializer();

    /**
     * The kind of clustering prefix this actually is.
     *
     * The kind {@code STATIC_CLUSTERING} is only implemented by {@link Clustering#STATIC_CLUSTERING} and {@code CLUSTERING} is
     * implemented by the {@link Clustering} class. The rest is used by {@link ClusteringBound} and {@link ClusteringBoundary}.
     */
    public enum Kind
    {
        // WARNING: the ordering of that enum matters because we use ordinal() in the serialization

        EXCL_END_BOUND              (0, -1, ByteSource.LT_NEXT_COMPONENT),
        INCL_START_BOUND            (0, -1, ByteSource.LT_NEXT_COMPONENT),
        EXCL_END_INCL_START_BOUNDARY(0, -1, ByteSource.LT_NEXT_COMPONENT),
        STATIC_CLUSTERING           (1, -1, ByteSource.LT_NEXT_COMPONENT + 1),
        CLUSTERING                  (2,  0, ByteSource.NEXT_COMPONENT),
        INCL_END_EXCL_START_BOUNDARY(3,  1, ByteSource.GT_NEXT_COMPONENT),
        INCL_END_BOUND              (3,  1, ByteSource.GT_NEXT_COMPONENT),
        EXCL_START_BOUND            (3,  1, ByteSource.GT_NEXT_COMPONENT);

        private final int comparison;

        /**
         * Return the comparison of this kind to CLUSTERING.
         * For bounds/boundaries, this basically tells us if we sort before or after our clustering values.
         */
        public final int comparedToClustering;

        public final int byteSourceValue;

        Kind(int comparison, int comparedToClustering, int byteSourceValue)
        {
            this.comparison = comparison;
            this.comparedToClustering = comparedToClustering;
            this.byteSourceValue = byteSourceValue;
        }

        /**
         * Compares the 2 provided kind.
         * <p>
         * Note: this should be used instead of {@link #compareTo} when comparing clustering prefixes. We do
         * not override that latter method because it is final for an enum.
         */
        public static int compare(Kind k1, Kind k2)
        {
            return Integer.compare(k1.comparison, k2.comparison);
        }

        /**
         * Returns the inverse of the current kind.
         * <p>
         * This invert both start into end (and vice-versa) and inclusive into exclusive (and vice-versa).
         *
         * @return the invert of this kind. For instance, if this kind is an exlusive start, this return
         * an inclusive end.
         */
        public Kind invert()
        {
            switch (this)
            {
                case EXCL_START_BOUND:              return INCL_END_BOUND;
                case INCL_START_BOUND:              return EXCL_END_BOUND;
                case EXCL_END_BOUND:                return INCL_START_BOUND;
                case INCL_END_BOUND:                return EXCL_START_BOUND;
                case EXCL_END_INCL_START_BOUNDARY:  return INCL_END_EXCL_START_BOUNDARY;
                case INCL_END_EXCL_START_BOUNDARY:  return EXCL_END_INCL_START_BOUNDARY;
                default:                            return this;
            }
        }

        public boolean isBound()
        {
            switch (this)
            {
                case INCL_START_BOUND:
                case INCL_END_BOUND:
                case EXCL_START_BOUND:
                case EXCL_END_BOUND:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isBoundary()
        {
            switch (this)
            {
                case INCL_END_EXCL_START_BOUNDARY:
                case EXCL_END_INCL_START_BOUNDARY:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isStart()
        {
            switch (this)
            {
                case INCL_START_BOUND:
                case EXCL_END_INCL_START_BOUNDARY:
                case INCL_END_EXCL_START_BOUNDARY:
                case EXCL_START_BOUND:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isEnd()
        {
            switch (this)
            {
                case INCL_END_BOUND:
                case EXCL_END_INCL_START_BOUNDARY:
                case INCL_END_EXCL_START_BOUNDARY:
                case EXCL_END_BOUND:
                    return true;
                default:
                    return false;
            }
        }

        public boolean isOpen(boolean reversed)
        {
            return isBoundary() || (reversed ? isEnd() : isStart());
        }

        public boolean isClose(boolean reversed)
        {
            return isBoundary() || (reversed ? isStart() : isEnd());
        }

        public Kind closeBoundOfBoundary(boolean reversed)
        {
            assert isBoundary();
            return reversed
                 ? (this == INCL_END_EXCL_START_BOUNDARY ? EXCL_START_BOUND : INCL_START_BOUND)
                 : (this == INCL_END_EXCL_START_BOUNDARY ? INCL_END_BOUND : EXCL_END_BOUND);
        }

        public Kind openBoundOfBoundary(boolean reversed)
        {
            assert isBoundary();
            return reversed
                 ? (this == INCL_END_EXCL_START_BOUNDARY ? INCL_END_BOUND : EXCL_END_BOUND)
                 : (this == INCL_END_EXCL_START_BOUNDARY ? EXCL_START_BOUND : INCL_START_BOUND);
        }

        /*
         * Returns a terminator value for this clustering type that is suitable for byte comparison.
         * Inclusive starts / exclusive ends need a lower value than ByteSource.NEXT_COMPONENT and the clustering byte,
         * exclusive starts / inclusive ends -- a higher.
         */
        public int asByteComparableValue()
        {
            return byteSourceValue;
        }
    }

    public Kind kind();

    /**
     * The number of values in this prefix.
     *
     * There can't be more values that the this is a prefix of has of clustering columns.
     *
     * @return the number of values in this prefix.
     */
    public int size();

    /**
     * Retrieves the ith value of this prefix.
     *
     * @param i the index of the value to retrieve. Must be such that {@code 0 <= i < size()}.
     *
     * @return the ith value of this prefix. Note that a value can be {@code null}.
     */
    public ByteBuffer get(int i);

    /**
     * Adds the data of this clustering prefix to the provided Hasher instance.
     *
     * @param hasher the Hasher instance to which to add this prefix.
     */
    public void digest(Hasher hasher);

    /**
     * The size of the data hold by this prefix.
     *
     * @return the size of the data hold by this prefix (this is not the size of the object in memory, just
     * the size of the data it stores).
     */
    public int dataSize();

    /**
     * Generates a proper string representation of the prefix.
     *
     * @param metadata the metadata for the table the clustering prefix is of.
     * @return a human-readable string representation fo this prefix.
     */
    public String toString(TableMetadata metadata);

    /*
     * TODO: we should stop using Clustering for partition keys. Maybe we can add
     * a few methods to DecoratedKey so we don't have to (note that while using a Clustering
     * allows to use buildBound(), it's actually used for partition keys only when every restriction
     * is an equal, so we could easily create a specific method for keys for that.
     */
    default ByteBuffer serializeAsPartitionKey()
    {
        if (size() == 1)
            return get(0);

        ByteBuffer[] values = new ByteBuffer[size()];
        for (int i = 0; i < size(); i++)
            values[i] = get(i);
        return CompositeType.build(values);
    }
    /**
     * Produce a human-readable representation of the clustering given the list of types.
     * Easier to access than metadata for debugging.
     */
    public default String clusteringString(List<AbstractType<?>> types)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(kind()).append('(');
        for (int i = 0; i < size(); i++)
        {
            if (i > 0)
                sb.append(", ");
            sb.append(types.get(i).getString(get(i)));
        }
        return sb.append(')').toString();
    }

    /**
     * The values of this prefix as an array.
     * <p>
     * Please note that this may or may not require an array creation. So 1) you should *not*
     * modify the returned array and 2) it's more efficient to use {@link #size()} and
     * {@link #get} unless you actually need an array.
     *
     * @return the values for this prefix as an array.
     */
    public ByteBuffer[] getRawValues();

    public static class Serializer
    {
        public void serialize(ClusteringPrefix clustering, DataOutputPlus out, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            // We shouldn't serialize static clusterings
            assert clustering.kind() != Kind.STATIC_CLUSTERING;
            if (clustering.kind() == Kind.CLUSTERING)
            {
                out.writeByte(clustering.kind().ordinal());
                Clustering.serializer.serialize((Clustering)clustering, out, version, types);
            }
            else
            {
                ClusteringBoundOrBoundary.serializer.serialize((ClusteringBoundOrBoundary)clustering, out, version, types);
            }
        }

        public void skip(DataInputPlus in, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            Kind kind = Kind.values()[in.readByte()];
            // We shouldn't serialize static clusterings
            assert kind != Kind.STATIC_CLUSTERING;
            if (kind == Kind.CLUSTERING)
                Clustering.serializer.skip(in, version, types);
            else
                ClusteringBoundOrBoundary.serializer.skipValues(in, kind, version, types);
        }

        public ClusteringPrefix deserialize(DataInputPlus in, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            Kind kind = Kind.values()[in.readByte()];
            // We shouldn't serialize static clusterings
            assert kind != Kind.STATIC_CLUSTERING;
            if (kind == Kind.CLUSTERING)
                return Clustering.serializer.deserialize(in, version, types);
            else
                return ClusteringBoundOrBoundary.serializer.deserializeValues(in, kind, version, types);
        }

        public long serializedSize(ClusteringPrefix clustering, ClusteringVersion version, List<AbstractType<?>> types)
        {
            // We shouldn't serialize static clusterings
            assert clustering.kind() != Kind.STATIC_CLUSTERING;
            if (clustering.kind() == Kind.CLUSTERING)
                return 1 + Clustering.serializer.serializedSize((Clustering)clustering, version, types);
            else
                return ClusteringBoundOrBoundary.serializer.serializedSize((ClusteringBoundOrBoundary)clustering, version, types);
        }

        void serializeValuesWithoutSize(ClusteringPrefix clustering, DataOutputPlus out, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            int offset = 0;
            int clusteringSize = clustering.size();
            // serialize in batches of 32, to avoid garbage when deserializing headers
            while (offset < clusteringSize)
            {
                // we micro-batch the headers, so that we can incur fewer method calls,
                // and generate no garbage on deserialization;
                // we piggyback on vint encoding so that, typically, only 1 byte is used per 32 clustering values,
                // i.e. more than we ever expect to see
                int limit = Math.min(clusteringSize, offset + 32);
                out.writeUnsignedVInt(makeHeader(clustering, offset, limit));
                while (offset < limit)
                {
                    ByteBuffer v = clustering.get(offset);
                    if (v != null && v.hasRemaining())
                        types.get(offset).writeValue(v, out);
                    offset++;
                }
            }
        }

        long valuesWithoutSizeSerializedSize(ClusteringPrefix clustering, ClusteringVersion version, List<AbstractType<?>> types)
        {
            long result = 0;
            int offset = 0;
            int clusteringSize = clustering.size();
            while (offset < clusteringSize)
            {
                int limit = Math.min(clusteringSize, offset + 32);
                result += TypeSizes.sizeofUnsignedVInt(makeHeader(clustering, offset, limit));
                offset = limit;
            }
            for (int i = 0; i < clusteringSize; i++)
            {
                ByteBuffer v = clustering.get(i);
                if (v == null || !v.hasRemaining())
                    continue; // handled in the header

                result += types.get(i).writtenLength(v);
            }
            return result;
        }

        ByteBuffer[] deserializeValuesWithoutSize(DataInputPlus in, int size, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            // Callers of this method should handle the case where size = 0 (in all case we want to return a special value anyway).
            assert size > 0;
            ByteBuffer[] values = new ByteBuffer[size];
            int offset = 0;
            while (offset < size)
            {
                long header = in.readUnsignedVInt();
                int limit = Math.min(size, offset + 32);
                while (offset < limit)
                {
                    values[offset] = isNull(header, offset)
                                ? null
                                : (isEmpty(header, offset) ? ByteBufferUtil.EMPTY_BYTE_BUFFER : types.get(offset).readValue(in, DatabaseDescriptor.getMaxValueSize()));
                    offset++;
                }
            }
            return values;
        }

        void skipValuesWithoutSize(DataInputPlus in, int size, ClusteringVersion version, List<AbstractType<?>> types) throws IOException
        {
            // Callers of this method should handle the case where size = 0 (in all case we want to return a special value anyway).
            assert size > 0;
            int offset = 0;
            while (offset < size)
            {
                long header = in.readUnsignedVInt();
                int limit = Math.min(size, offset + 32);
                while (offset < limit)
                {
                    if (!isNull(header, offset) && !isEmpty(header, offset))
                         types.get(offset).skipValue(in);
                    offset++;
                }
            }
        }

        /**
         * Whatever the type of a given clustering column is, its value can always be either empty or null. So we at least need to distinguish those
         * 2 values, and because we want to be able to store fixed width values without appending their (fixed) size first, we need a way to encode
         * empty values too. So for that, every clustering prefix includes a "header" that contains 2 bits per element in the prefix. For each element,
         * those 2 bits encode whether the element is null, empty, or none of those.
         */
        private static long makeHeader(ClusteringPrefix clustering, int offset, int limit)
        {
            long header = 0;
            for (int i = offset ; i < limit ; i++)
            {
                ByteBuffer v = clustering.get(i);
                // no need to do modulo arithmetic for i, since the left-shift execute on the modulus of RH operand by definition
                if (v == null)
                    header |= (1L << (i * 2) + 1);
                else if (!v.hasRemaining())
                    header |= (1L << (i * 2));
            }
            return header;
        }

        // no need to do modulo arithmetic for i, since the left-shift execute on the modulus of RH operand by definition
        private static boolean isNull(long header, int i)
        {
            long mask = 1L << (i * 2) + 1;
            return (header & mask) != 0;
        }

        // no need to do modulo arithmetic for i, since the left-shift execute on the modulus of RH operand by definition
        private static boolean isEmpty(long header, int i)
        {
            long mask = 1L << (i * 2);
            return (header & mask) != 0;
        }
    }

    /**
     * Helper class that makes the deserialization of clustering prefixes faster.
     * <p>
     * The main reason for this is that when we deserialize rows from sstables, there is many cases where we have
     * a bunch of rows to skip at the beginning of an index block because those rows are before the requested slice.
     * This class make sure we can answer the question "is the next row on disk before the requested slice" with as
     * little work as possible. It does that by providing a comparison method that deserialize only what is needed
     * to decide of the comparison.
     */
    public static class Deserializer
    {
        private final ClusteringComparator comparator;
        private final DataInputPlus in;
        private final SerializationHeader serializationHeader;

        private boolean nextIsRow;
        private long nextHeader;

        private int nextSize;
        private ClusteringPrefix.Kind nextKind;
        private int deserializedSize;
        private ByteBuffer[] nextValues;


        public Deserializer(ClusteringComparator comparator, DataInputPlus in, SerializationHeader header)
        {
            this.comparator = comparator;
            this.in = in;
            this.serializationHeader = header;
        }

        public void prepare(int flags, int extendedFlags) throws IOException
        {
            if (UnfilteredSerializer.isStatic(extendedFlags))
                throw new IOException("Corrupt flags value for clustering prefix (isStatic flag set): " + flags);

            this.nextIsRow = UnfilteredSerializer.kind(flags) == Unfiltered.Kind.ROW;
            this.nextKind = nextIsRow ? Kind.CLUSTERING : ClusteringPrefix.Kind.values()[in.readByte()];
            this.nextSize = nextIsRow ? comparator.size() : in.readUnsignedShort();
            this.deserializedSize = 0;

            // The point of the deserializer is that some of the clustering prefix won't actually be used (because they are not
            // within the bounds of the query), and we want to reduce allocation for them. So we only reuse the values array
            // between elements if 1) we haven't returned the previous element (if we have, nextValues will be null) and 2)
            // nextValues is of the proper size. Note that the 2nd condition may not hold for range tombstone bounds, but all
            // rows have a fixed size clustering, so we'll still save in the common case.
            if (nextValues == null)
            {
                this.nextValues = new ByteBuffer[nextSize];
            }
            else if (nextValues.length < nextSize)
            {
                // keep the already allocated ByteBuffers too
                this.nextValues = Arrays.copyOf(nextValues, nextSize);
            }
        }

        public int compareNextTo(ClusteringBoundOrBoundary bound) throws IOException
        {
            if (bound == ClusteringBound.TOP)
                return -1;

            for (int i = 0; i < bound.size(); i++)
            {
                if (!hasComponent(i))
                    return nextKind.comparedToClustering;

                int cmp = comparator.compareComponent(i, nextValues[i], bound.get(i));
                if (cmp != 0)
                    return cmp;
            }

            if (bound.size() == nextSize)
                return Kind.compare(nextKind, bound.kind());

            // We know that we'll have exited already if nextSize < bound.size
            return -bound.kind().comparedToClustering;
        }

        private boolean hasComponent(int i) throws IOException
        {
            if (i >= nextSize)
                return false;

            while (deserializedSize <= i)
                deserializeOne();

            return true;
        }

        private boolean deserializeOne() throws IOException
        {
            if (deserializedSize == nextSize)
                return false;

            if ((deserializedSize % 32) == 0)
                nextHeader = in.readUnsignedVInt();

            int i = deserializedSize++;

            final ByteBuffer nextValue;
            if (Serializer.isNull(nextHeader, i))
            {
                nextValue = null;
            }
            else if (Serializer.isEmpty(nextHeader, i))
            {
                nextValue = ByteBufferUtil.EMPTY_BYTE_BUFFER;
            }
            else
            {
                final AbstractType<?> type = serializationHeader.clusteringTypes().get(i);

                // null is valid return from read, as well as a new BB, but we're hopeful about reuse here
                nextValue = type.readValue(in, DatabaseDescriptor.getMaxValueSize(), nextValues[i]);
            }
            nextValues[i] = nextValue;
            return true;
        }

        private void deserializeAll() throws IOException
        {
            while (deserializeOne())
                continue;
        }

        public ClusteringBoundOrBoundary deserializeNextBound() throws IOException
        {
            assert !nextIsRow;
            deserializeAll();
            final ByteBuffer[] values = detachValues();
            ClusteringBoundOrBoundary bound = ClusteringBoundOrBoundary.create(nextKind, values);
            return bound;
        }

        public Clustering deserializeNextClustering() throws IOException
        {
            assert nextIsRow;
            deserializeAll();

            final ByteBuffer[] values = detachValues();
            Clustering clustering = Clustering.make(values);
            return clustering;
        }

        private ByteBuffer[] detachValues()
        {
            if (nextSize == 0)
                return EMPTY_BUFFER_ARRAY;
            final ByteBuffer[] values = Arrays.copyOf(nextValues, nextSize);
            Arrays.fill(nextValues, 0, nextSize , null);
            return values;
        }

        public ClusteringPrefix.Kind skipNext() throws IOException
        {
            for (int i = deserializedSize; i < nextSize; i++)
            {
                if ((i % 32) == 0)
                    nextHeader = in.readUnsignedVInt();
                if (!Serializer.isNull(nextHeader, i) && !Serializer.isEmpty(nextHeader, i))
                    serializationHeader.clusteringTypes().get(i).skipValue(in);
            }
            deserializedSize = nextSize;
            return nextKind;
        }
    }
}
