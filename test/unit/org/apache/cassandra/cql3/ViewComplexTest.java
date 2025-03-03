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

package org.apache.cassandra.cql3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.datastax.driver.core.exceptions.OperationTimedOutException;
import org.apache.cassandra.concurrent.SEPExecutor;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.FBUtilities;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import com.google.common.base.Objects;

public class
ViewComplexTest extends CQLTester
{
    ProtocolVersion protocolVersion = ProtocolVersion.V4;
    private final List<String> views = new ArrayList<>();

    @BeforeClass
    public static void startup()
    {
        requireNetwork();
    }

    @Before
    public void begin()
    {
        views.clear();
    }

    @After
    public void end() throws Throwable
    {
        for (String viewName : views)
            executeNet(protocolVersion, "DROP MATERIALIZED VIEW " + viewName);
    }

    private void createView(String name, String query) throws Throwable
    {
        try
        {
            executeNet(protocolVersion, String.format(query, name));
            // If exception is thrown, the view will not be added to the list; since it shouldn't have been created, this is
            // the desired behavior
            views.add(name);
        }
        catch (OperationTimedOutException ex)
        {
            // ... except for timeout, when we actually do not know whether the view was created or not
            views.add(name);
            throw ex;
        }
    }

    private void updateView(String query, Object... params) throws Throwable
    {
        updateViewWithFlush(query, false, params);
    }

    private void updateViewWithFlush(String query, boolean flush, Object... params) throws Throwable
    {
        executeNet(protocolVersion, query, params);
        while (!(((SEPExecutor) Stage.VIEW_MUTATION.executor()).getPendingTaskCount() == 0
                && ((SEPExecutor) Stage.VIEW_MUTATION.executor()).getActiveTaskCount() == 0))
        {
            Thread.sleep(1);
        }
        if (flush)
            Keyspace.open(keyspace()).flush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
    }

    // for now, unselected column cannot be fully supported, SEE CASSANDRA-11500
    @Ignore
    @Test
    public void testPartialDeleteUnselectedColumn() throws Throwable
    {
        boolean flush = true;
        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        createTable("CREATE TABLE %s (k int, c int, a int, b int, PRIMARY KEY (k, c))");
        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT k,c FROM %%s WHERE k IS NOT NULL AND c IS NOT NULL PRIMARY KEY (k,c)");
        Keyspace ks = Keyspace.open(keyspace());
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateView("UPDATE %s USING TIMESTAMP 10 SET b=1 WHERE k=1 AND c=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRows(execute("SELECT * from %s"), row(1, 1, null, 1));
        assertRows(execute("SELECT * from mv"), row(1, 1));
        updateView("DELETE b FROM %s USING TIMESTAMP 11 WHERE k=1 AND c=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));
        updateView("UPDATE %s USING TIMESTAMP 1 SET a=1 WHERE k=1 AND c=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRows(execute("SELECT * from %s"), row(1, 1, 1, null));
        assertRows(execute("SELECT * from mv"), row(1, 1));

        execute("truncate %s;");

        // removal generated by unselected column should not shadow PK update with smaller timestamp
        updateViewWithFlush("UPDATE %s USING TIMESTAMP 18 SET a=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, 1, null));
        assertRows(execute("SELECT * from mv"), row(1, 1));

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 20 SET a=null WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"));
        assertRows(execute("SELECT * from mv"));

        updateViewWithFlush("INSERT INTO %s(k,c) VALUES(1,1) USING TIMESTAMP 15", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1));
    }

    @Test
    public void testPartialDeleteSelectedColumnWithFlush() throws Throwable
    {
        testPartialDeleteSelectedColumn(true);
    }

    @Test
    public void testPartialDeleteSelectedColumnWithoutFlush() throws Throwable
    {
        testPartialDeleteSelectedColumn(false);
    }

    private void testPartialDeleteSelectedColumn(boolean flush) throws Throwable
    {
        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        createTable("CREATE TABLE %s (k int, c int, a int, b int, e int, f int, PRIMARY KEY (k, c))");
        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT a, b, c, k FROM %%s WHERE k IS NOT NULL AND c IS NOT NULL PRIMARY KEY (k,c)");
        Keyspace ks = Keyspace.open(keyspace());
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 10 SET b=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, 1, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, 1));

        updateViewWithFlush("DELETE b FROM %s USING TIMESTAMP 11 WHERE k=1 AND c=1", flush);
        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 1 SET a=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, 1, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, 1, null));

        updateViewWithFlush("DELETE a FROM %s USING TIMESTAMP 1 WHERE k=1 AND c=1", flush);
        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));

        // view livenessInfo should not be affected by selected column ts or tb
        updateViewWithFlush("INSERT INTO %s(k,c) VALUES(1,1) USING TIMESTAMP 0", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 12 SET b=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, 1, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, 1));

        updateViewWithFlush("DELETE b FROM %s USING TIMESTAMP 13 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateViewWithFlush("DELETE FROM %s USING TIMESTAMP 14 WHERE k=1 AND c=1", flush);
        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));

        updateViewWithFlush("INSERT INTO %s(k,c) VALUES(1,1) USING TIMESTAMP 15", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateViewWithFlush("UPDATE %s USING TTL 3 SET b=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, 1, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, 1));

        TimeUnit.SECONDS.sleep(4);

        assertRows(execute("SELECT * from %s"), row(1, 1, null, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateViewWithFlush("DELETE FROM %s USING TIMESTAMP 15 WHERE k=1 AND c=1", flush);
        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));

        execute("truncate %s;");

        // removal generated by unselected column should not shadow selected column with smaller timestamp
        updateViewWithFlush("UPDATE %s USING TIMESTAMP 18 SET e=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, null, null, 1, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 18 SET e=null WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"));
        assertRows(execute("SELECT * from mv"));

        updateViewWithFlush("UPDATE %s USING TIMESTAMP 16 SET a=1 WHERE k=1 AND c=1", flush);
        assertRows(execute("SELECT * from %s"), row(1, 1, 1, null, null, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, 1, null));
    }

    @Test
    public void testUpdateColumnInViewPKWithTTLWithFlush() throws Throwable
    {
        // CASSANDRA-13657
        testUpdateColumnInViewPKWithTTL(true);
    }

    @Test
    public void testUpdateColumnInViewPKWithTTLWithoutFlush() throws Throwable
    {
        // CASSANDRA-13657
        testUpdateColumnInViewPKWithTTL(false);
    }

    private void testUpdateColumnInViewPKWithTTL(boolean flush) throws Throwable
    {
        // CASSANDRA-13657 if base column used in view pk is ttled, then view row is considered dead
        createTable("create table %s (k int primary key, a int, b int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE k IS NOT NULL AND a IS NOT NULL PRIMARY KEY (a, k)");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateView("UPDATE %s SET a = 1 WHERE k = 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"), row(1, 1, null));
        assertRows(execute("SELECT * from mv"), row(1, 1, null));

        updateView("DELETE a FROM %s WHERE k = 1");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));

        updateView("INSERT INTO %s (k) VALUES (1);");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"), row(1, null, null));
        assertEmpty(execute("SELECT * from mv"));

        updateView("UPDATE %s USING TTL 5 SET a = 10 WHERE k = 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"), row(1, 10, null));
        assertRows(execute("SELECT * from mv"), row(10, 1, null));

        updateView("UPDATE %s SET b = 100 WHERE k = 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"), row(1, 10, 100));
        assertRows(execute("SELECT * from mv"), row(10, 1, 100));

        Thread.sleep(5000);

        // 'a' is TTL of 5 and removed.
        assertRows(execute("SELECT * from %s"), row(1, null, 100));
        assertEmpty(execute("SELECT * from mv"));
        assertEmpty(execute("SELECT * from mv WHERE k = ? AND a = ?", 1, 10));

        updateView("DELETE b FROM %s WHERE k=1");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT * from %s"), row(1, null, null));
        assertEmpty(execute("SELECT * from mv"));

        updateView("DELETE FROM %s WHERE k=1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertEmpty(execute("SELECT * from %s"));
        assertEmpty(execute("SELECT * from mv"));
    }

    @Test
    public void testUpdateColumnNotInViewWithFlush() throws Throwable
    {
        testUpdateColumnNotInView(true);
    }

    @Test
    public void testUpdateColumnNotInViewWithoutFlush() throws Throwable
    {
        // CASSANDRA-13127
        testUpdateColumnNotInView(false);
    }

    private void testUpdateColumnNotInView(boolean flush) throws Throwable
    {
        // CASSANDRA-13127: if base column not selected in view are alive, then pk of view row should be alive
        String baseTable = createTable("create table %s (p int, c int, v1 int, v2 int, primary key(p, c))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT p, c FROM %%s WHERE p IS NOT NULL AND c IS NOT NULL PRIMARY KEY (c, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateView("UPDATE %s USING TIMESTAMP 0 SET v1 = 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0), row(0, 0, 1, null));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        updateView("DELETE v1 FROM %s USING TIMESTAMP 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertEmpty(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));
        assertEmpty(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));

        // shadowed by tombstone
        updateView("UPDATE %s USING TIMESTAMP 1 SET v1 = 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertEmpty(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));
        assertEmpty(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));

        updateView("UPDATE %s USING TIMESTAMP 2 SET v2 = 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0), row(0, 0, null, 1));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        updateView("DELETE v1 FROM %s USING TIMESTAMP 3 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0), row(0, 0, null, 1));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        updateView("DELETE v2 FROM %s USING TIMESTAMP 4 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertEmpty(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));
        assertEmpty(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));

        updateView("UPDATE %s USING TTL 3 SET v2 = 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0), row(0, 0, null, 1));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        Thread.sleep(TimeUnit.SECONDS.toMillis(3));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));

        updateView("UPDATE %s SET v2 = 1 WHERE p = 0 AND c = 0");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0), row(0, 0, null, 1));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        assertInvalidMessage(String.format("Cannot drop column v2 on base table %s with materialized views", baseTable), "ALTER TABLE %s DROP v2");
        // // drop unselected base column, unselected metadata should be removed, thus view row is dead
        // updateView("ALTER TABLE %s DROP v2");
        // assertRowsIgnoringOrder(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));
        // assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));
        // assertRowsIgnoringOrder(execute("SELECT * from %s"));
        // assertRowsIgnoringOrder(execute("SELECT * from mv"));
    }

    @Test
    public void testPartialUpdateWithUnselectedCollectionsWithFlush() throws Throwable
    {
        testPartialUpdateWithUnselectedCollections(true);
    }

    @Test
    public void testPartialUpdateWithUnselectedCollectionsWithoutFlush() throws Throwable
    {
        testPartialUpdateWithUnselectedCollections(false);
    }

    public void testPartialUpdateWithUnselectedCollections(boolean flush) throws Throwable
    {
        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        String baseTable = createTable("CREATE TABLE %s (k int, c int, a int, b int, l list<int>, s set<int>, m map<int,int>, PRIMARY KEY (k, c))");
        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT a, b, c, k FROM %%s WHERE k IS NOT NULL AND c IS NOT NULL PRIMARY KEY (c, k)");
        Keyspace ks = Keyspace.open(keyspace());
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateView("UPDATE %s SET l=l+[1,2,3] WHERE k = 1 AND c = 1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateView("UPDATE %s SET l=l-[1,2] WHERE k = 1 AND c = 1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, null));

        updateView("UPDATE %s SET b=3 WHERE k=1 AND c=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRows(execute("SELECT * from mv"), row(1, 1, null, 3));

        updateView("UPDATE %s SET b=null, l=l-[3], s=s-{3} WHERE k = 1 AND c = 1");
        if (flush)
        {
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
            ks.getColumnFamilyStore("mv").forceMajorCompaction();
        }
        assertRowsIgnoringOrder(execute("SELECT k,c,a,b from %s"));
        assertRowsIgnoringOrder(execute("SELECT * from mv"));

        updateView("UPDATE %s SET m=m+{3:3}, l=l-[1], s=s-{2} WHERE k = 1 AND c = 1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,c,a,b from %s"), row(1, 1, null, null));
        assertRowsIgnoringOrder(execute("SELECT * from mv"), row(1, 1, null, null));

        assertInvalidMessage(String.format("Cannot drop column m on base table %s with materialized views", baseTable), "ALTER TABLE %s DROP m");
        // executeNet(protocolVersion, "ALTER TABLE %s DROP m");
        // ks.getColumnFamilyStore("mv").forceMajorCompaction();
        // assertRowsIgnoringOrder(execute("SELECT k,c,a,b from %s WHERE k = 1 AND c = 1"));
        // assertRowsIgnoringOrder(execute("SELECT * from mv WHERE k = 1 AND c = 1"));
        // assertRowsIgnoringOrder(execute("SELECT k,c,a,b from %s"));
        // assertRowsIgnoringOrder(execute("SELECT * from mv"));
    }

    @Test
    public void testUnselectedColumnsTTLWithFlush() throws Throwable
    {
        // CASSANDRA-13127
        testUnselectedColumnsTTL(true);
    }

    @Test
    public void testUnselectedColumnsTTLWithoutFlush() throws Throwable
    {
        // CASSANDRA-13127
        testUnselectedColumnsTTL(false);
    }

    private void testUnselectedColumnsTTL(boolean flush) throws Throwable
    {
        // CASSANDRA-13127 not ttled unselected column in base should keep view row alive
        createTable("create table %s (p int, c int, v int, primary key(p, c))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT p, c FROM %%s WHERE p IS NOT NULL AND c IS NOT NULL PRIMARY KEY (c, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        updateViewWithFlush("INSERT INTO %s (p, c) VALUES (0, 0) USING TTL 3;", flush);

        updateViewWithFlush("UPDATE %s USING TTL 1000 SET v = 0 WHERE p = 0 and c = 0;", flush);

        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        Thread.sleep(3000);

        UntypedResultSet.Row row = execute("SELECT v, ttl(v) from %s WHERE c = ? AND p = ?", 0, 0).one();
        assertTrue("row should have value of 0", row.getInt("v") == 0);
        assertTrue("row should have ttl less than 1000", row.getInt("ttl(v)") < 1000);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        updateViewWithFlush("DELETE FROM %s WHERE p = 0 and c = 0;", flush);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0));

        updateViewWithFlush("INSERT INTO %s (p, c) VALUES (0, 0) ", flush);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        // already have a live row, no need to apply the unselected cell ttl
        updateViewWithFlush("UPDATE %s USING TTL 3 SET v = 0 WHERE p = 0 and c = 0;", flush);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));

        updateViewWithFlush("INSERT INTO %s (p, c) VALUES (1, 1) USING TTL 3", flush);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 1, 1), row(1, 1));

        Thread.sleep(4000);

        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 0, 0), row(0, 0));
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 1, 1));

        // unselected should keep view row alive
        updateViewWithFlush("UPDATE %s SET v = 0 WHERE p = 1 and c = 1;", flush);
        assertRowsIgnoringOrder(execute("SELECT * from mv WHERE c = ? AND p = ?", 1, 1), row(1, 1));

    }

    @Test
    public void testRangeDeletionWithFlush() throws Throwable
    {
        testRangeDeletion(true);
    }

    @Test
    public void testRangeDeletionWithoutFlush() throws Throwable
    {
        testRangeDeletion(false);
    }

    public void testRangeDeletion(boolean flush) throws Throwable
    {
        // for partition range deletion, need to know that existing row is shadowed instead of not existed.
        createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());

        createView("mv_test1",
                   "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (a, b)");

        Keyspace ks = Keyspace.open(keyspace());
        ks.getColumnFamilyStore("mv_test1").disableAutoCompaction();

        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?) using timestamp 0", 1, 1, 1, 1);
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * FROM mv_test1"), row(1, 1, 1, 1));

        // remove view row
        updateView("UPDATE %s using timestamp 1 set b = null WHERE a=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * FROM mv_test1"));
        // remove base row, no view updated generated.
        updateView("DELETE FROM %s using timestamp 2 where a=1");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * FROM mv_test1"));

        // restor view row with b,c column. d is still tombstone
        updateView("UPDATE %s using timestamp 3 set b = 1,c = 1 where a=1"); // upsert
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT * FROM mv_test1"), row(1, 1, 1, null));
    }

    @Test
    public void testBaseTTLWithSameTimestampTest() throws Throwable
    {
        // CASSANDRA-13127 when liveness timestamp tie, greater localDeletionTime should win if both are expiring.
        createTable("create table %s (p int, c int, v int, primary key(p, c))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        updateView("INSERT INTO %s (p, c, v) VALUES (0, 0, 0) using timestamp 1;");

        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        updateView("INSERT INTO %s (p, c, v) VALUES (0, 0, 0) USING TTL 3 and timestamp 1;");

        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        Thread.sleep(4000);

        assertEmpty(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));

        // reversed order
        execute("truncate %s;");

        updateView("INSERT INTO %s (p, c, v) VALUES (0, 0, 0) USING TTL 3 and timestamp 1;");

        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        updateView("INSERT INTO %s (p, c, v) VALUES (0, 0, 0) USING timestamp 1;");

        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        Thread.sleep(4000);

        assertEmpty(execute("SELECT * from %s WHERE c = ? AND p = ?", 0, 0));

    }

    @Test
    public void testCommutativeRowDeletionFlush() throws Throwable
    {
        // CASSANDRA-13409
        testCommutativeRowDeletion(true);
    }

    @Test
    public void testCommutativeRowDeletionWithoutFlush() throws Throwable
    {
        // CASSANDRA-13409
        testCommutativeRowDeletion(false);
    }

    private void testCommutativeRowDeletion(boolean flush) throws Throwable
    {
        // CASSANDRA-13409 new update should not resurrect previous deleted data in view
        createTable("create table %s (p int primary key, v1 int, v2 int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p is not null and v1 is not null primary key (v1, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        // sstable-1, Set initial values TS=1
        updateView("Insert into %s (p, v1, v2) values (3, 1, 3) using timestamp 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v2, WRITETIME(v2) from mv WHERE v1 = ? AND p = ?", 1, 3), row(3, 1L));
        // sstable-2
        updateView("Delete from %s using timestamp 2 where p = 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"));
        // sstable-3
        updateView("Insert into %s (p, v1) values (3, 1) using timestamp 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, null, null));
        // sstable-4
        updateView("UPdate %s using timestamp 4 set v1 = 2 where p = 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(2, 3, null, null));
        // sstable-5
        updateView("UPdate %s using timestamp 5 set v1 = 1 where p = 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, null, null));

        if (flush)
        {
            // compact sstable 2 and 4, 5;
            ColumnFamilyStore cfs = ks.getColumnFamilyStore("mv");
            List<String> sstables = cfs.getLiveSSTables()
                                       .stream()
                                       .sorted((s1, s2) -> s1.descriptor.generation - s2.descriptor.generation)
                                       .map(s -> s.getFilename())
                                       .collect(Collectors.toList());
            String dataFiles = String.join(",", Arrays.asList(sstables.get(1), sstables.get(3), sstables.get(4)));
            CompactionManager.instance.forceUserDefinedCompaction(dataFiles);
            assertEquals(3, cfs.getLiveSSTables().size());
        }
        // regular tombstone should be retained after compaction
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, null, null));
    }

    @Test
    public void testUnselectedColumnWithExpiredLivenessInfo() throws Throwable
    {
        boolean flush = true;
        createTable("create table %s (k int, c int, a int, b int, PRIMARY KEY(k, c))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select k,c,b from %%s where c is not null and k is not null primary key (c, k);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        // sstable-1, Set initial values TS=1
        updateViewWithFlush("UPDATE %s SET a = 1 WHERE k = 1 AND c = 1;", flush);

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE k = 1 AND c = 1;"),
                                row(1, 1, 1, null));
        assertRowsIgnoringOrder(execute("SELECT k,c,b from mv WHERE k = 1 AND c = 1;"),
                                row(1, 1, null));

        // sstable-2
        updateViewWithFlush("INSERT INTO %s(k,c) VALUES(1,1) USING TTL 5", flush);

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE k = 1 AND c = 1;"),
                                row(1, 1, 1, null));
        assertRowsIgnoringOrder(execute("SELECT k,c,b from mv WHERE k = 1 AND c = 1;"),
                                row(1, 1, null));

        Thread.sleep(5001);

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE k = 1 AND c = 1;"),
                                row(1, 1, 1, null));
        assertRowsIgnoringOrder(execute("SELECT k,c,b from mv WHERE k = 1 AND c = 1;"),
                                row(1, 1, null));

        // sstable-3
        updateViewWithFlush("Update %s set a = null where k = 1 AND c = 1;", flush);

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE k = 1 AND c = 1;"));
        assertRowsIgnoringOrder(execute("SELECT k,c,b from mv WHERE k = 1 AND c = 1;"));

        // sstable-4
        updateViewWithFlush("Update %s USING TIMESTAMP 1 set b = 1 where k = 1 AND c = 1;", flush);

        assertRowsIgnoringOrder(execute("SELECT * from %s WHERE k = 1 AND c = 1;"),
                                row(1, 1, null, 1));
        assertRowsIgnoringOrder(execute("SELECT k,c,b from mv WHERE k = 1 AND c = 1;"),
                                row(1, 1, 1));
    }

    @Test
    public void testUpdateWithColumnTimestampSmallerThanPkWithFlush() throws Throwable
    {
        testUpdateWithColumnTimestampSmallerThanPk(true);
    }

    @Test
    public void testUpdateWithColumnTimestampSmallerThanPkWithoutFlush() throws Throwable
    {
        testUpdateWithColumnTimestampSmallerThanPk(false);
    }

    public void testUpdateWithColumnTimestampSmallerThanPk(boolean flush) throws Throwable
    {
        createTable("create table %s (p int primary key, v1 int, v2 int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p is not null and v1 is not null primary key (v1, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        // reset value
        updateView("Insert into %s (p, v1, v2) values (3, 1, 3) using timestamp 6;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, 3, 6L));
        // increase pk's timestamp to 20
        updateView("Insert into %s (p) values (3) using timestamp 20;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, 3, 6L));
        // change v1's to 2 and remove existing view row with ts7
        updateView("UPdate %s using timestamp 7 set v1 = 2 where p = 3;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(2, 3, 3, 6L));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv limit 1"), row(2, 3, 3, 6L));
        // change v1's to 1 and remove existing view row with ts8
        updateView("UPdate %s using timestamp 8 set v1 = 1 where p = 3;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, 3, 6L));
    }

    @Test
    public void testExpiredLivenessLimitWithFlush() throws Throwable
    {
        // CASSANDRA-13883
        testExpiredLivenessLimit(true);
    }

    @Test
    public void testExpiredLivenessLimitWithoutFlush() throws Throwable
    {
        // CASSANDRA-13883
        testExpiredLivenessLimit(false);
    }

    private void testExpiredLivenessLimit(boolean flush) throws Throwable
    {
        createTable("CREATE TABLE %s (k int PRIMARY KEY, a int, b int);");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv1", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE k IS NOT NULL AND a IS NOT NULL PRIMARY KEY (k, a);");
        createView("mv2", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE k IS NOT NULL AND a IS NOT NULL PRIMARY KEY (a, k);");
        ks.getColumnFamilyStore("mv1").disableAutoCompaction();
        ks.getColumnFamilyStore("mv2").disableAutoCompaction();

        for (int i = 1; i <= 100; i++)
            updateView("INSERT INTO %s(k, a, b) VALUES (?, ?, ?);", i, i, i);
        for (int i = 1; i <= 100; i++)
        {
            if (i % 50 == 0)
                continue;
            // create expired liveness
            updateView("DELETE a FROM %s WHERE k = ?;", i);
        }
        if (flush)
        {
            ks.getColumnFamilyStore("mv1").forceBlockingFlush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
            ks.getColumnFamilyStore("mv2").forceBlockingFlush(ColumnFamilyStore.FlushReason.UNIT_TESTS);
        }

        for (String view : Arrays.asList("mv1", "mv2"))
        {
            // paging
            assertEquals(1, executeNetWithPaging(protocolVersion, String.format("SELECT k,a,b FROM %s limit 1", view), 1).all().size());
            assertEquals(2, executeNetWithPaging(protocolVersion, String.format("SELECT k,a,b FROM %s limit 2", view), 1).all().size());
            assertEquals(2, executeNetWithPaging(protocolVersion, String.format("SELECT k,a,b FROM %s", view), 1).all().size());
            assertRowsNet(protocolVersion, executeNetWithPaging(protocolVersion, String.format("SELECT k,a,b FROM %s ", view), 1),
                          row(50, 50, 50),
                          row(100, 100, 100));
            // limit
            assertEquals(1, execute(String.format("SELECT k,a,b FROM %s limit 1", view)).size());
            assertRowsIgnoringOrder(execute(String.format("SELECT k,a,b FROM %s limit 2", view)),
                                    row(50, 50, 50),
                                    row(100, 100, 100));
        }
    }

    @Test
    public void testUpdateWithColumnTimestampBiggerThanPkWithFlush() throws Throwable
    {
        // CASSANDRA-11500
        testUpdateWithColumnTimestampBiggerThanPk(true);
    }

    @Test
    public void testUpdateWithColumnTimestampBiggerThanPkWithoutFlush() throws Throwable
    {
        // CASSANDRA-11500
        testUpdateWithColumnTimestampBiggerThanPk(false);
    }

    public void testUpdateWithColumnTimestampBiggerThanPk(boolean flush) throws Throwable
    {
        // CASSANDRA-11500 able to shadow old view row with column ts greater tahn pk's ts and re-insert the view row
        String baseTable = createTable("CREATE TABLE %s (k int PRIMARY KEY, a int, b int);");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE k IS NOT NULL AND a IS NOT NULL PRIMARY KEY (k, a);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();
        updateView("DELETE FROM %s USING TIMESTAMP 0 WHERE k = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // sstable-1, Set initial values TS=1
        updateView("INSERT INTO %s(k, a, b) VALUES (1, 1, 1) USING TIMESTAMP 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 1, 1));
        updateView("UPDATE %s USING TIMESTAMP 10 SET b = 2 WHERE k = 1;");
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 1, 2));
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 1, 2));
        updateView("UPDATE %s USING TIMESTAMP 2 SET a = 2 WHERE k = 1;");
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 2, 2));
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        ks.getColumnFamilyStore("mv").forceMajorCompaction();
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 2, 2));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv limit 1"), row(1, 2, 2));
        updateView("UPDATE %s USING TIMESTAMP 11 SET a = 1 WHERE k = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 1, 2));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from %s"), row(1, 1, 2));

        // set non-key base column as tombstone, view row is removed with shadowable
        updateView("UPDATE %s USING TIMESTAMP 12 SET a = null WHERE k = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from %s"), row(1, null, 2));

        // column b should be alive
        updateView("UPDATE %s USING TIMESTAMP 13 SET a = 1 WHERE k = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from mv"), row(1, 1, 2));
        assertRowsIgnoringOrder(execute("SELECT k,a,b from %s"), row(1, 1, 2));

        assertInvalidMessage(String.format("Cannot drop column a on base table %s with materialized views", baseTable), "ALTER TABLE %s DROP a");
    }

    @Test
    public void testNonBaseColumnInViewPkWithFlush() throws Throwable
    {
        testNonBaseColumnInViewPk(true);
    }

    @Test
    public void testNonBaseColumnInViewPkWithoutFlush() throws Throwable
    {
        testNonBaseColumnInViewPk(true);
    }

    public void testNonBaseColumnInViewPk(boolean flush) throws Throwable
    {
        createTable("create table %s (p1 int, p2 int, v1 int, v2 int, primary key (p1,p2))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p1 is not null and p2 is not null primary key (p2, p1)"
                           + " with gc_grace_seconds=5;");
        ColumnFamilyStore cfs = ks.getColumnFamilyStore("mv");
        cfs.disableAutoCompaction();

        updateView("UPDATE %s USING TIMESTAMP 1 set v1 =1 where p1 = 1 AND p2 = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"), row(1, 1, 1, null));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"), row(1, 1, 1, null));

        updateView("UPDATE %s USING TIMESTAMP 2 set v1 = null, v2 = 1 where p1 = 1 AND p2 = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"), row(1, 1, null, 1));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"), row(1, 1, null, 1));

        updateView("UPDATE %s USING TIMESTAMP 2 set v2 = null where p1 = 1 AND p2 = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"));

        updateView("INSERT INTO %s (p1,p2) VALUES(1,1) USING TIMESTAMP 3;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"), row(1, 1, null, null));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"), row(1, 1, null, null));

        updateView("DELETE FROM %s USING TIMESTAMP 4 WHERE p1 =1 AND p2 = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"));

        updateView("UPDATE %s USING TIMESTAMP 5 set v2 = 1 where p1 = 1 AND p2 = 1;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from %s"), row(1, 1, null, 1));
        assertRowsIgnoringOrder(execute("SELECT p1, p2, v1, v2 from mv"), row(1, 1, null, 1));
    }

    @Test
    public void testStrictLivenessTombstone() throws Throwable
    {
        createTable("create table %s (p int primary key, v1 int, v2 int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p is not null and v1 is not null primary key (v1, p)"
                           + " with gc_grace_seconds=5;");
        ColumnFamilyStore cfs = ks.getColumnFamilyStore("mv");
        cfs.disableAutoCompaction();

        updateView("Insert into %s (p, v1, v2) values (1, 1, 1) ;");
        assertRowsIgnoringOrder(execute("SELECT p, v1, v2 from mv"), row(1, 1, 1));

        updateView("Update %s set v1 = null WHERE p = 1");
        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p, v1, v2 from mv"));

        cfs.forceMajorCompaction(); // before gc grace second, strict-liveness tombstoned dead row remains
        assertEquals(1, cfs.getLiveSSTables().size());

        Thread.sleep(6000);
        assertEquals(1, cfs.getLiveSSTables().size()); // no auto compaction.

        cfs.forceMajorCompaction(); // after gc grace second, no data left
        assertEquals(0, cfs.getLiveSSTables().size());

        updateView("Update %s using ttl 5 set v1 = 1 WHERE p = 1");
        FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        assertRowsIgnoringOrder(execute("SELECT p, v1, v2 from mv"), row(1, 1, 1));

        cfs.forceMajorCompaction(); // before ttl+gc_grace_second, strict-liveness ttled dead row remains
        assertEquals(1, cfs.getLiveSSTables().size());
        assertRowsIgnoringOrder(execute("SELECT p, v1, v2 from mv"), row(1, 1, 1));

        Thread.sleep(5500); // after expired, before gc_grace_second
        cfs.forceMajorCompaction();// before ttl+gc_grace_second, strict-liveness ttled dead row remains
        assertEquals(1, cfs.getLiveSSTables().size());
        assertRowsIgnoringOrder(execute("SELECT p, v1, v2 from mv"));

        Thread.sleep(5500); // after expired + gc_grace_second
        assertEquals(1, cfs.getLiveSSTables().size()); // no auto compaction.

        cfs.forceMajorCompaction(); // after gc grace second, no data left
        assertEquals(0, cfs.getLiveSSTables().size());
    }

    @Test
    public void testCellTombstoneAndShadowableTombstonesWithFlush() throws Throwable
    {
        testCellTombstoneAndShadowableTombstones(true);
    }

    @Test
    public void testCellTombstoneAndShadowableTombstonesWithoutFlush() throws Throwable
    {
        testCellTombstoneAndShadowableTombstones(false);
    }

    private void testCellTombstoneAndShadowableTombstones(boolean flush) throws Throwable
    {
        createTable("create table %s (p int primary key, v1 int, v2 int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p is not null and v1 is not null primary key (v1, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        // sstable 1, Set initial values TS=1
        updateView("Insert into %s (p, v1, v2) values (3, 1, 3) using timestamp 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v2, WRITETIME(v2) from mv WHERE v1 = ? AND p = ?", 1, 3), row(3, 1L));
        // sstable 2
        updateView("UPdate %s using timestamp 2 set v2 = null where p = 3");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v2, WRITETIME(v2) from mv WHERE v1 = ? AND p = ?", 1, 3),
                                row(null, null));
        // sstable 3
        updateView("UPdate %s using timestamp 3 set v1 = 2 where p = 3");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(2, 3, null, null));
        // sstable 4
        updateView("UPdate %s using timestamp 4 set v1 = 1 where p = 3");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, null, null));

        if (flush)
        {
            // compact sstable 2 and 3;
            ColumnFamilyStore cfs = ks.getColumnFamilyStore("mv");
            List<String> sstables = cfs.getLiveSSTables()
                                       .stream()
                                       .sorted(Comparator.comparingInt(s -> s.descriptor.generation))
                                       .map(s -> s.getFilename())
                                       .collect(Collectors.toList());
            String dataFiles = String.join(",", Arrays.asList(sstables.get(1), sstables.get(2)));
            CompactionManager.instance.forceUserDefinedCompaction(dataFiles);
        }
        // cell-tombstone in sstable 4 is not compacted away, because the shadowable tombstone is shadowed by new row.
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, null, null));
        assertRowsIgnoringOrder(execute("SELECT v1, p, v2, WRITETIME(v2) from mv limit 1"), row(1, 3, null, null));
    }

    @Test
    public void complexTimestampDeletionTestWithFlush() throws Throwable
    {
        complexTimestampWithbaseNonPKColumnsInViewPKDeletionTest(true);
        complexTimestampWithbasePKColumnsInViewPKDeletionTest(true);
    }

    @Test
    public void complexTimestampDeletionTestWithoutFlush() throws Throwable
    {
        complexTimestampWithbaseNonPKColumnsInViewPKDeletionTest(false);
        complexTimestampWithbasePKColumnsInViewPKDeletionTest(false);
    }

    private void complexTimestampWithbasePKColumnsInViewPKDeletionTest(boolean flush) throws Throwable
    {
        createTable("create table %s (p1 int, p2 int, v1 int, v2 int, primary key(p1, p2))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv2",
                   "create materialized view %s as select * from %%s where p1 is not null and p2 is not null primary key (p2, p1);");
        ks.getColumnFamilyStore("mv2").disableAutoCompaction();

        // Set initial values TS=1
        updateView("Insert into %s (p1, p2, v1, v2) values (1, 2, 3, 4) using timestamp 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, v2, WRITETIME(v2) from mv2 WHERE p1 = ? AND p2 = ?", 1, 2),
                                row(3, 4, 1L));
        // remove row/mv TS=2
        updateView("Delete from %s using timestamp 2 where p1 = 1 and p2 = 2;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // view are empty
        assertRowsIgnoringOrder(execute("SELECT * from mv2"));
        // insert PK with TS=3
        updateView("Insert into %s (p1, p2) values (1, 2) using timestamp 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // deleted column in MV remained dead
        assertRowsIgnoringOrder(execute("SELECT * from mv2"), row(2, 1, null, null));

        ks.getColumnFamilyStore("mv2").forceMajorCompaction();
        assertRowsIgnoringOrder(execute("SELECT * from mv2"), row(2, 1, null, null));

        // reset values
        updateView("Insert into %s (p1, p2, v1, v2) values (1, 2, 3, 4) using timestamp 10;");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, v2, WRITETIME(v2) from mv2 WHERE p1 = ? AND p2 = ?", 1, 2),
                                row(3, 4, 10L));

        updateView("UPDATE %s using timestamp 20 SET v2 = 5 WHERE p1 = 1 and p2 = 2");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, v2, WRITETIME(v2) from mv2 WHERE p1 = ? AND p2 = ?", 1, 2),
                                row(3, 5, 20L));

        updateView("DELETE FROM %s using timestamp 10 WHERE p1 = 1 and p2 = 2");
        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v1, v2, WRITETIME(v2) from mv2 WHERE p1 = ? AND p2 = ?", 1, 2),
                                row(null, 5, 20L));
    }

    public void complexTimestampWithbaseNonPKColumnsInViewPKDeletionTest(boolean flush) throws Throwable
    {
        createTable("create table %s (p int primary key, v1 int, v2 int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv",
                   "create materialized view %s as select * from %%s where p is not null and v1 is not null primary key (v1, p);");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        // Set initial values TS=1
        updateView("Insert into %s (p, v1, v2) values (3, 1, 5) using timestamp 1;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRowsIgnoringOrder(execute("SELECT v2, WRITETIME(v2) from mv WHERE v1 = ? AND p = ?", 1, 3), row(5, 1L));
        // remove row/mv TS=2
        updateView("Delete from %s using timestamp 2 where p = 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // view are empty
        assertRowsIgnoringOrder(execute("SELECT * from mv"));
        // insert PK with TS=3
        updateView("Insert into %s (p, v1) values (3, 1) using timestamp 3;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // deleted column in MV remained dead
        assertRowsIgnoringOrder(execute("SELECT * from mv"), row(1, 3, null));

        // insert values TS=2, it should be considered dead due to previous tombstone
        updateView("Insert into %s (p, v1, v2) values (3, 1, 5) using timestamp 2;");

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));
        // deleted column in MV remained dead
        assertRowsIgnoringOrder(execute("SELECT * from mv"), row(1, 3, null));
        assertRowsIgnoringOrder(execute("SELECT * from mv limit 1"), row(1, 3, null));

        // insert values TS=2, it should be considered dead due to previous tombstone
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 3 SET v2 = ? WHERE p = ?", 4, 3);

        if (flush)
            FBUtilities.waitOnFutures(ks.flush(ColumnFamilyStore.FlushReason.UNIT_TESTS));

        assertRows(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, 4, 3L));

        ks.getColumnFamilyStore("mv").forceMajorCompaction();
        assertRows(execute("SELECT v1, p, v2, WRITETIME(v2) from mv"), row(1, 3, 4, 3L));
        assertRows(execute("SELECT v1, p, v2, WRITETIME(v2) from mv limit 1"), row(1, 3, 4, 3L));
    }

    @Test
    public void testMVWithDifferentColumnsWithFlush() throws Throwable
    {
        testMVWithDifferentColumns(true);
    }

    @Test
    public void testMVWithDifferentColumnsWithoutFlush() throws Throwable
    {
        testMVWithDifferentColumns(false);
    }

    private void testMVWithDifferentColumns(boolean flush) throws Throwable
    {
        createTable("CREATE TABLE %s (a int, b int, c int, d int, e int, f int, PRIMARY KEY(a, b))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        List<String> viewNames = new ArrayList<>();
        List<String> mvStatements = Arrays.asList(
                                                  // all selected
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (a,b)",
                                                  // unselected e,f
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT a,b,c,d FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (a,b)",
                                                  // no selected
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT a,b FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (a,b)",
                                                  // all selected, re-order keys
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (b,a)",
                                                  // unselected e,f, re-order keys
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT a,b,c,d FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (b,a)",
                                                  // no selected, re-order keys
                                                  "CREATE MATERIALIZED VIEW %s AS SELECT a,b FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL PRIMARY KEY (b,a)");

        Keyspace ks = Keyspace.open(keyspace());

        for (int i = 0; i < mvStatements.size(); i++)
        {
            String name = "mv" + i;
            viewNames.add(name);
            createView(name, mvStatements.get(i));
            ks.getColumnFamilyStore(name).disableAutoCompaction();
        }

        // insert
        updateViewWithFlush("INSERT INTO %s (a,b,c,d,e,f) VALUES(1,1,1,1,1,1) using timestamp 1", flush);
        assertBaseViews(row(1, 1, 1, 1, 1, 1), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 2 SET c=0, d=0 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, 0, 0, 1, 1), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 2 SET e=0, f=0 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, 0, 0, 0, 0), viewNames);

        updateViewWithFlush("DELETE FROM %s using timestamp 2 WHERE a=1 AND b=1", flush);
        assertBaseViews(null, viewNames);

        // partial update unselected, selected
        updateViewWithFlush("UPDATE %s using timestamp 3 SET f=1 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, null, null, null, 1), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 4 SET e = 1, f=null WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, null, null, 1, null), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 4 SET e = null WHERE a=1 AND b=1", flush);
        assertBaseViews(null, viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 5 SET c = 1 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, 1, null, null, null), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 5 SET c = null WHERE a=1 AND b=1", flush);
        assertBaseViews(null, viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 6 SET d = 1 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, null, 1, null, null), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 7 SET d = null WHERE a=1 AND b=1", flush);
        assertBaseViews(null, viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 8 SET f = 1 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, null, null, null, 1), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 6 SET c = 1 WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, 1, null, null, 1), viewNames);

        // view row still alive due to c=1@6
        updateViewWithFlush("UPDATE %s using timestamp 8 SET f = null WHERE a=1 AND b=1", flush);
        assertBaseViews(row(1, 1, 1, null, null, null), viewNames);

        updateViewWithFlush("UPDATE %s using timestamp 6 SET c = null WHERE a=1 AND b=1", flush);
        assertBaseViews(null, viewNames);
    }

    private void assertBaseViews(Object[] row, List<String> viewNames) throws Throwable
    {
        UntypedResultSet result = execute("SELECT * FROM %s");
        if (row == null)
            assertRowsIgnoringOrder(result);
        else
            assertRowsIgnoringOrder(result, row);
        for (int i = 0; i < viewNames.size(); i++)
            assertBaseView(result, execute(String.format("SELECT * FROM %s", viewNames.get(i))), viewNames.get(i));
    }

    private void assertBaseView(UntypedResultSet base, UntypedResultSet view, String mv)
    {
        List<ColumnSpecification> baseMeta = base.metadata();
        List<ColumnSpecification> viewMeta = view.metadata();

        Iterator<UntypedResultSet.Row> iter = base.iterator();
        Iterator<UntypedResultSet.Row> viewIter = view.iterator();

        List<UntypedResultSet.Row> baseData = com.google.common.collect.Lists.newArrayList(iter);
        List<UntypedResultSet.Row> viewData = com.google.common.collect.Lists.newArrayList(viewIter);

        if (baseData.size() != viewData.size())
            fail(String.format("Mismatch number of rows in view %s: <%s>, in base <%s>",
                               mv,
                               makeRowStrings(view),
                               makeRowStrings(base)));
        if (baseData.size() == 0)
            return;
        if (viewData.size() != 1)
            fail(String.format("Expect only one row in view %s, but got <%s>",
                               mv,
                               makeRowStrings(view)));

        UntypedResultSet.Row row = baseData.get(0);
        UntypedResultSet.Row viewRow = viewData.get(0);

        Map<String, ByteBuffer> baseValues = new HashMap<>();
        for (int j = 0; j < baseMeta.size(); j++)
        {
            ColumnSpecification column = baseMeta.get(j);
            ByteBuffer actualValue = row.getBytes(column.name.toString());
            baseValues.put(column.name.toString(), actualValue);
        }
        for (int j = 0; j < viewMeta.size(); j++)
        {
            ColumnSpecification column = viewMeta.get(j);
            String name = column.name.toString();
            ByteBuffer viewValue = viewRow.getBytes(name);
            if (!baseValues.containsKey(name))
            {
                fail(String.format("Extra column: %s with value %s in view", name, column.type.compose(viewValue)));
            }
            else if (!Objects.equal(baseValues.get(name), viewValue))
            {
                fail(String.format("Non equal column: %s, expected <%s> but got <%s>",
                                   name,
                                   column.type.compose(baseValues.get(name)),
                                   column.type.compose(viewValue)));
            }
        }
    }
}
