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
package org.apache.cassandra.io.sstable.metadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.io.sstable.SSTable;
import org.apache.cassandra.io.sstable.format.trieindex.TrieIndexFormat;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.commitlog.CommitLogPosition;
import org.apache.cassandra.db.commitlog.IntervalSet;
import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.Version;
import org.apache.cassandra.io.sstable.format.big.BigFormat;
import org.apache.cassandra.io.util.BufferedDataOutputStreamPlus;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.io.util.RandomAccessReader;
import org.apache.cassandra.serializers.UTF8Serializer;
import org.apache.cassandra.utils.Throwables;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetadataSerializerTest
{
    @BeforeClass
    public static void initDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    @Test
    public void testSerialization() throws IOException
    {
        Map<MetadataType, MetadataComponent> originalMetadata = constructMetadata(false);

        MetadataSerializer serializer = new MetadataSerializer();
        File statsFile = serialize(originalMetadata, serializer, SSTableFormat.Type.current().info.getLatestVersion());

        Descriptor desc = new Descriptor(statsFile.getParentFile(), "", "", 0, SSTableFormat.Type.current());
        try (RandomAccessReader in = RandomAccessReader.open(statsFile))
        {
            Map<MetadataType, MetadataComponent> deserialized = serializer.deserialize(desc, in, EnumSet.allOf(MetadataType.class));

            for (MetadataType type : MetadataType.values())
            {
                assertEquals(originalMetadata.get(type), deserialized.get(type));
            }
        }
    }

    @Test
    public void testHistogramSterilization() throws IOException
    {
        Map<MetadataType, MetadataComponent> originalMetadata = constructMetadata(false);

        // Modify the histograms to overflow:
        StatsMetadata originalStats = (StatsMetadata) originalMetadata.get(MetadataType.STATS);
        originalStats.estimatedCellPerPartitionCount.add(Long.MAX_VALUE);
        originalStats.estimatedPartitionSize.add(Long.MAX_VALUE);
        assertTrue(originalStats.estimatedCellPerPartitionCount.isOverflowed());
        assertTrue(originalStats.estimatedPartitionSize.isOverflowed());

        // Serialize w/ overflowed histograms:
        MetadataSerializer serializer = new MetadataSerializer();
        File statsFile = serialize(originalMetadata, serializer, BigFormat.latestVersion);
        Descriptor desc = new Descriptor(statsFile.getParentFile(), "", "", 0, SSTableFormat.Type.BIG);

        try (RandomAccessReader in = RandomAccessReader.open(statsFile))
        {
            // Deserialie and verify that the two histograms have had their overflow buckets cleared:
            Map<MetadataType, MetadataComponent> deserialized = serializer.deserialize(desc, in, EnumSet.allOf(MetadataType.class));
            StatsMetadata deserializedStats = (StatsMetadata) deserialized.get(MetadataType.STATS);
            assertFalse(deserializedStats.estimatedCellPerPartitionCount.isOverflowed());
            assertFalse(deserializedStats.estimatedPartitionSize.isOverflowed());
        }
    }

    public File serialize(Map<MetadataType, MetadataComponent> metadata, MetadataSerializer serializer, Version version)
    throws IOException
    {
        // Serialize to tmp file
        File statsFile = FileUtils.createTempFile(Component.STATS.name, null);
        try (DataOutputStreamPlus out = new BufferedDataOutputStreamPlus(new FileOutputStream(statsFile)))
        {
            serializer.serialize(metadata, out, version);
        }
        return statsFile;
    }

    public Map<MetadataType, MetadataComponent> constructMetadata(boolean withNulls)
    {
        CommitLogPosition club = new CommitLogPosition(11L, 12);
        CommitLogPosition cllb = new CommitLogPosition(9L, 12);

        TableMetadata cfm = SchemaLoader.clusteringSASICFMD("ks1", "cf1").build();
        MetadataCollector collector = new MetadataCollector(cfm.comparator)
                                      .commitLogIntervals(new IntervalSet<>(cllb, club));

        String partitioner = RandomPartitioner.class.getCanonicalName();
        double bfFpChance = 0.1;
        collector.updateClusteringValues(Clustering.make(UTF8Type.instance.decompose("abc"), Int32Type.instance.decompose(123)));
        collector.updateClusteringValues(Clustering.make(UTF8Type.instance.decompose("cba"), withNulls ? null : Int32Type.instance.decompose(234)));
        return collector.finalizeMetadata(partitioner, bfFpChance, 0, null, false, SerializationHeader.make(cfm, Collections.emptyList()));
    }

    private void testVersions(String... versions) throws Throwable
    {
        Throwable t = null;
        for (int oldIdx = 0; oldIdx < versions.length; oldIdx++)
        {
            for (int newIdx = oldIdx; newIdx < versions.length; newIdx++)
            {
                try
                {
                    testOldReadsNew(versions[oldIdx], versions[newIdx]);
                }
                catch (Exception | AssertionError e)
                {
                    t = Throwables.merge(t, new AssertionError("Failed to test " + versions[oldIdx] + " -> " + versions[newIdx], e));
                }
            }
        }
        if (t != null)
        {
            throw t;
        }
    }

    @Test
    public void testMVersions() throws Throwable
    {
        Assume.assumeTrue(SSTableFormat.Type.current() == SSTableFormat.Type.BIG);
        testVersions("ma", "mb", "mc", "md");
    }

    @Test
    public void testNVersions() throws Throwable
    {
        Assume.assumeTrue(SSTableFormat.Type.current() == SSTableFormat.Type.BIG);
        testVersions("na");
    }

    @Test
    public void testAVersions() throws Throwable
    {
        Assume.assumeTrue(SSTableFormat.Type.current() == SSTableFormat.Type.BTI);
        testVersions("aa", "ac", "ad");
    }

    @Test
    public void testBVersions() throws Throwable
    {
        Assume.assumeTrue(SSTableFormat.Type.current() == SSTableFormat.Type.BTI);
        testVersions("ba", "bb");
    }

    @Test
    public void testCVersions() throws Throwable
    {
        Assume.assumeTrue(SSTableFormat.Type.current() == SSTableFormat.Type.BTI);
        testVersions("ca");
    }

    public void testOldReadsNew(String oldV, String newV) throws IOException
    {
        Map<MetadataType, MetadataComponent> originalMetadata = constructMetadata(true);

        MetadataSerializer serializer = new MetadataSerializer();
        // Write metadata in two minor formats.
        File statsFileLb = serialize(originalMetadata, serializer, SSTableFormat.Type.current().info.getVersion(newV));
        File statsFileLa = serialize(originalMetadata, serializer, SSTableFormat.Type.current().info.getVersion(oldV));
        // Reading both as earlier version should yield identical results.
        SSTableFormat.Type stype = SSTableFormat.Type.current();
        Descriptor desc = new Descriptor(stype.info.getVersion(oldV), statsFileLb.getParentFile(), "", "", 0, stype);
        try (RandomAccessReader inLb = RandomAccessReader.open(statsFileLb);
             RandomAccessReader inLa = RandomAccessReader.open(statsFileLa))
        {
            Map<MetadataType, MetadataComponent> deserializedLb = serializer.deserialize(desc, inLb, EnumSet.allOf(MetadataType.class));
            Map<MetadataType, MetadataComponent> deserializedLa = serializer.deserialize(desc, inLa, EnumSet.allOf(MetadataType.class));

            for (MetadataType type : MetadataType.values())
            {
                assertEquals(deserializedLa.get(type), deserializedLb.get(type));

                if (MetadataType.STATS != type)
                    assertEquals(originalMetadata.get(type), deserializedLb.get(type));
            }
        }
    }

    @Test
    public void pendingRepairCompatibility()
    {
        Version mc = BigFormat.instance.getVersion("mc");
        assertFalse(mc.hasPendingRepair());
        Version na = BigFormat.instance.getVersion("na");
        assertTrue(na.hasPendingRepair());
    }
}
