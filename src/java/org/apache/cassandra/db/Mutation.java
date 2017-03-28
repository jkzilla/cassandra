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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.Completable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import org.apache.cassandra.concurrent.NettyRxScheduler;
import org.apache.cassandra.db.commitlog.CommitLogPosition;
import org.apache.commons.lang3.StringUtils;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.WriteVerbs.WriteVersion;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.SerializationHelper;
import org.apache.cassandra.io.util.DataInputPlus;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Serializer;
import org.apache.cassandra.utils.versioning.VersionDependent;
import org.apache.cassandra.utils.versioning.Versioned;

// TODO convert this to a Builder pattern instead of encouraging M.add directly,
// which is less-efficient since we have to keep a mutable HashMap around
public class Mutation implements IMutation
{
    /**
     * The raw serializer is used for local serialization (commit log, hints, schema), we need to expose
     * MutationSerializer because some callers need to specify the serialization flag, {@link SerializationHelper.Flag}
     * when calling deserialize().
     */
    public static final Versioned<EncodingVersion, MutationSerializer> rawSerializers = EncodingVersion.versioned(MutationSerializer::new);

    /**
     * The serializer returns a raw serializer for callers that prefer to retrieve it by WriteVersion
     * and do not need to specify the serialization flag, {@link SerializationHelper.Flag} when calling deserialize().
     */
    public static final Versioned<WriteVersion, Serializer<Mutation>> serializers = WriteVersion.versioned(v -> rawSerializers.get(v.encodingVersion));

    // todo this is redundant
    // when we remove it, also restore SerializationsTest.testMutationRead to not regenerate new Mutations each test
    private final String keyspaceName;

    private final DecoratedKey key;
    // map of column family id to mutations for that column family.
    private final Map<TableId, PartitionUpdate> modifications;

    // Time at which this mutation was instantiated
    public final long createdAt = System.currentTimeMillis();

    // keep track of when mutation has started waiting for a MV partition lock
    public long viewLockAcquireStart = 0;

    private boolean cdcEnabled = false;

    public Mutation(String keyspaceName, DecoratedKey key)
    {
        this(keyspaceName, key, new HashMap<>());
    }

    public Mutation(PartitionUpdate update)
    {
        this(update.metadata().keyspace, update.partitionKey(), Collections.singletonMap(update.metadata().id, update));
    }

    protected Mutation(String keyspaceName, DecoratedKey key, Map<TableId, PartitionUpdate> modifications)
    {
        this.keyspaceName = keyspaceName;
        this.key = key;
        this.modifications = modifications;
        for (PartitionUpdate pu : modifications.values())
            cdcEnabled |= pu.metadata().params.cdc;
    }

    public Mutation copy()
    {
        return new Mutation(keyspaceName, key, new HashMap<>(modifications));
    }

    public Mutation without(Set<TableId> tableIds)
    {
        if (tableIds.isEmpty())
            return this;

        Mutation copy = copy();

        copy.modifications.keySet().removeAll(tableIds);

        copy.cdcEnabled = false;
        for (PartitionUpdate pu : modifications.values())
            copy.cdcEnabled |= pu.metadata().params.cdc;

        return copy;
    }

    public Mutation without(TableId tableId)
    {
        return without(Collections.singleton(tableId));
    }

    public String getKeyspaceName()
    {
        return keyspaceName;
    }

    public Collection<TableId> getTableIds()
    {
        return modifications.keySet();
    }

    public DecoratedKey key()
    {
        return key;
    }

    public Collection<PartitionUpdate> getPartitionUpdates()
    {
        return modifications.values();
    }

    public PartitionUpdate getPartitionUpdate(TableMetadata table)
    {
        return table == null ? null : modifications.get(table.id);
    }

    /**
     * Adds PartitionUpdate to the local set of modifications.
     * Assumes no updates for the Table this PartitionUpdate impacts.
     *
     * @param update PartitionUpdate to append to Modifications list
     * @return Mutation this mutation
     * @throws IllegalArgumentException If PartitionUpdate for duplicate table is passed as argument
     */
    public Mutation add(PartitionUpdate update)
    {
        assert update != null;
        assert update.partitionKey().getPartitioner() == key.getPartitioner();

        cdcEnabled |= update.metadata().params.cdc;

        PartitionUpdate prev = modifications.put(update.metadata().id, update);
        if (prev != null)
            // developer error
            throw new IllegalArgumentException("Table " + update.metadata().name + " already has modifications in this mutation: " + prev);
        return this;
    }

    public PartitionUpdate get(TableMetadata metadata)
    {
        return modifications.get(metadata.id);
    }

    public boolean isEmpty()
    {
        return modifications.isEmpty();
    }

    /**
     * Creates a new mutation that merges all the provided mutations.
     *
     * @param mutations the mutations to merge together. All mutation must be
     * on the same keyspace and partition key. There should also be at least one
     * mutation.
     * @return a mutation that contains all the modifications contained in {@code mutations}.
     *
     * @throws IllegalArgumentException if not all the mutations are on the same
     * keyspace and key.
     */
    public static Mutation merge(List<Mutation> mutations)
    {
        assert !mutations.isEmpty();

        if (mutations.size() == 1)
            return mutations.get(0);

        Set<TableId> updatedTables = new HashSet<>();
        String ks = null;
        DecoratedKey key = null;
        for (Mutation mutation : mutations)
        {
            updatedTables.addAll(mutation.modifications.keySet());
            if (ks != null && !ks.equals(mutation.keyspaceName))
                throw new IllegalArgumentException();
            if (key != null && !key.equals(mutation.key))
                throw new IllegalArgumentException();
            ks = mutation.keyspaceName;
            key = mutation.key;
        }

        List<PartitionUpdate> updates = new ArrayList<>(mutations.size());
        Map<TableId, PartitionUpdate> modifications = new HashMap<>(updatedTables.size());
        for (TableId table : updatedTables)
        {
            for (Mutation mutation : mutations)
            {
                PartitionUpdate upd = mutation.modifications.get(table);
                if (upd != null)
                    updates.add(upd);
            }

            if (updates.isEmpty())
                continue;

            modifications.put(table, updates.size() == 1 ? updates.get(0) : PartitionUpdate.merge(updates));
            updates.clear();
        }
        return new Mutation(ks, key, modifications);
    }

    public Scheduler getScheduler()
    {
        return NettyRxScheduler.getForKey(getKeyspaceName(), key(), false);
    }

    public Completable applyAsync(boolean durableWrites, boolean isDroppable)
    {
        Keyspace ks = Keyspace.open(keyspaceName);
        return ks.apply(this, durableWrites, true, isDroppable);
    }

    public CompletableFuture<?> applyFuture()
    {
        CompletableFuture<?> ret = new CompletableFuture<>();
        applyAsync().subscribe(()-> ret.complete(null), ex -> ret.completeExceptionally(ex));
        return ret;
    }

    public Completable applyAsync()
    {
        return applyAsync(Keyspace.open(keyspaceName).getMetadata().params.durableWrites, true);
    }

    public void apply(boolean durableWrites, boolean isDroppable)
    {
        Keyspace.open(keyspaceName).apply(this, durableWrites, true, isDroppable).blockingAwait();
    }

    public void apply(boolean durableWrites)
    {
        applyAsync(durableWrites, true).blockingAwait();
    }

    /*
     * This is equivalent to calling commit. Applies the changes to
     * to the keyspace that is obtained by calling Keyspace.open().
     */
    public void apply()
    {
        apply(Keyspace.open(keyspaceName).getMetadata().params.durableWrites);
    }

    public void applyUnsafe()
    {
        apply(false);
    }

    public long getTimeout()
    {
        return DatabaseDescriptor.getWriteRpcTimeout();
    }

    public int smallestGCGS()
    {
        int gcgs = Integer.MAX_VALUE;
        for (PartitionUpdate update : getPartitionUpdates())
            gcgs = Math.min(gcgs, update.metadata().params.gcGraceSeconds);
        return gcgs;
    }

    public boolean trackedByCDC()
    {
        return cdcEnabled;
    }

    public String toString()
    {
        return toString(false);
    }

    public String toString(boolean shallow)
    {
        StringBuilder buff = new StringBuilder("Mutation(");
        buff.append("keyspace='").append(keyspaceName).append('\'');
        buff.append(", key='").append(ByteBufferUtil.bytesToHex(key.getKey())).append('\'');
        buff.append(", modifications=[");
        if (shallow)
        {
            List<String> cfnames = new ArrayList<>(modifications.size());
            for (TableId tableId : modifications.keySet())
            {
                TableMetadata cfm = Schema.instance.getTableMetadata(tableId);
                cfnames.add(cfm == null ? "-dropped-" : cfm.name);
            }
            buff.append(StringUtils.join(cfnames, ", "));
        }
        else
        {
            buff.append("\n  ").append(StringUtils.join(modifications.values(), "\n  ")).append('\n');
        }
        return buff.append("])").toString();
    }

    /**
     * Creates a new simple mutuation builder.
     *
     * @param keyspaceName the name of the keyspace this is a mutation for.
     * @param partitionKey the key of partition this if a mutation for.
     * @return a newly created builder.
     */
    public static SimpleBuilder simpleBuilder(String keyspaceName, DecoratedKey partitionKey)
    {
        return new SimpleBuilders.MutationBuilder(keyspaceName, partitionKey);
    }

    /**
     * Interface for building mutations geared towards human.
     * <p>
     * This should generally not be used when performance matters too much, but provides a more convenient interface to
     * build a mutation than using the class constructor when performance is not of the utmost importance.
     */
    public interface SimpleBuilder
    {
        /**
         * Sets the timestamp to use for the following additions to this builder or any derived (update or row) builder.
         *
         * @param timestamp the timestamp to use for following additions. If that timestamp hasn't been set, the current
         * time in microseconds will be used.
         * @return this builder.
         */
        public SimpleBuilder timestamp(long timestamp);

        /**
         * Sets the ttl to use for the following additions to this builder or any derived (update or row) builder.
         * <p>
         * Note that the for non-compact tables, this method must be called before any column addition for this
         * ttl to be used for the row {@code LivenessInfo}.
         *
         * @param ttl the ttl to use for following additions. If that ttl hasn't been set, no ttl will be used.
         * @return this builder.
         */
        public SimpleBuilder ttl(int ttl);

        /**
         * Adds an update for table identified by the provided metadata and return a builder for that partition.
         *
         * @param metadata the metadata of the table for which to add an update.
         * @return a builder for the partition identified by {@code metadata} (and the partition key for which this is a
         * mutation of).
         */
        public PartitionUpdate.SimpleBuilder update(TableMetadata metadata);

        /**
         * Adds an update for table identified by the provided name and return a builder for that partition.
         *
         * @param tableName the name of the table for which to add an update.
         * @return a builder for the partition identified by {@code metadata} (and the partition key for which this is a
         * mutation of).
         */
        public PartitionUpdate.SimpleBuilder update(String tableName);

        /**
         * Build the mutation represented by this builder.
         *
         * @return the built mutation.
         */
        public Mutation build();
    }

    public static class MutationSerializer extends VersionDependent<EncodingVersion> implements Serializer<Mutation>
    {
        private final PartitionUpdate.PartitionUpdateSerializer partitionUpdateSerializer;

        private MutationSerializer(EncodingVersion version)
        {
            super(version);
            this.partitionUpdateSerializer = PartitionUpdate.serializers.get(version);
        }

        public void serialize(Mutation mutation, DataOutputPlus out) throws IOException
        {
            /* serialize the modifications in the mutation */
            int size = mutation.modifications.size();
            out.writeUnsignedVInt(size);

            assert size > 0;
            for (Map.Entry<TableId, PartitionUpdate> entry : mutation.modifications.entrySet())
                partitionUpdateSerializer.serialize(entry.getValue(), out);
        }

        public Mutation deserialize(DataInputPlus in, SerializationHelper.Flag flag) throws IOException
        {
            int size = (int)in.readUnsignedVInt();
            assert size > 0;

            PartitionUpdate update = partitionUpdateSerializer.deserialize(in, flag);
            if (size == 1)
                return new Mutation(update);

            Map<TableId, PartitionUpdate> modifications = new HashMap<>(size);
            DecoratedKey dk = update.partitionKey();

            modifications.put(update.metadata().id, update);
            for (int i = 1; i < size; ++i)
            {
                update = partitionUpdateSerializer.deserialize(in, flag);
                modifications.put(update.metadata().id, update);
            }

            return new Mutation(update.metadata().keyspace, dk, modifications);
        }

        public Mutation deserialize(DataInputPlus in) throws IOException
        {
            return deserialize(in, SerializationHelper.Flag.FROM_REMOTE);
        }

        public long serializedSize(Mutation mutation)
        {
            long size = TypeSizes.sizeofUnsignedVInt(mutation.modifications.size());
            for (Map.Entry<TableId, PartitionUpdate> entry : mutation.modifications.entrySet())
                size += partitionUpdateSerializer.serializedSize(entry.getValue());

            return size;
        }
    }
}
