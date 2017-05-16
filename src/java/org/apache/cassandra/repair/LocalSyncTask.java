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
package org.apache.cassandra.repair;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.streaming.PreviewKind;
import org.apache.cassandra.streaming.ProgressInfo;
import org.apache.cassandra.streaming.StreamEvent;
import org.apache.cassandra.streaming.StreamEventHandler;
import org.apache.cassandra.streaming.StreamPlan;
import org.apache.cassandra.streaming.StreamState;
import org.apache.cassandra.streaming.StreamOperation;
import org.apache.cassandra.tracing.TraceState;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.RangeHash;

import static org.apache.cassandra.repair.StreamingRepairTask.REPAIR_STREAM_PLAN_DESCRIPTION;

/**
 * LocalSyncTask performs streaming between local(coordinator) node and remote replica.
 */
public class LocalSyncTask extends SyncTask implements StreamEventHandler
{
    private final TraceState state = Tracing.instance.get();

    private static final Logger logger = LoggerFactory.getLogger(LocalSyncTask.class);

    private final UUID pendingRepair;
    private final boolean pullRepair;

    public LocalSyncTask(RepairJobDesc desc, TreeResponse r1, TreeResponse r2, UUID pendingRepair, boolean pullRepair,
                         Executor taskExecutor, SyncTask next, Map<InetAddress, Set<RangeHash>> receivedRangeCache,
                         PreviewKind previewKind)
    {
        super(desc, r1, r2, taskExecutor, next, receivedRangeCache, previewKind);
        this.pendingRepair = pendingRepair;
        this.pullRepair = pullRepair;
    }


    @VisibleForTesting
    StreamPlan createStreamPlan(InetAddress dst, InetAddress preferred, List<Range<Token>> toRequest, List<Range<Token>> toTransfer)
    {
        StreamPlan plan = new StreamPlan(StreamOperation.REPAIR, 1, false, false, pendingRepair, previewKind)
                          .listeners(this)
                          .flushBeforeTransfer(pendingRepair == null)
                          // request ranges from the remote node
                          .requestRanges(dst, preferred, desc.keyspace, toRequest, desc.columnFamily);
        if (!pullRepair)
        {
            // send ranges to the remote node if we are not performing a pull repair
            plan.transferRanges(dst, preferred, desc.keyspace, toTransfer, desc.columnFamily);
        }

        return plan;
    }

    /**
     * Starts sending/receiving our list of differences to/from the remote endpoint: creates a callback
     * that will be called out of band once the streams complete.
     */
    @Override
    protected void startSync(List<Range<Token>> transferToLeft, List<Range<Token>> transferToRight)
    {
        InetAddress local = FBUtilities.getBroadcastAddress();
        // We can take anyone of the node as source or destination, however if one is localhost, we put at source to avoid a forwarding
        InetAddress dst = r2.endpoint.equals(local) ? r1.endpoint : r2.endpoint;
        List<Range<Token>> toRequest = r2.endpoint.equals(local) ? transferToRight : transferToLeft;
        List<Range<Token>> toTransfer = r2.endpoint.equals(local) ? transferToLeft : transferToRight;

        InetAddress preferred = SystemKeyspace.getPreferredIP(dst);

        String message = String.format("Performing streaming repair of %d ranges to %s%s",
                                       transferToLeft.size(), transferToLeft.size() != transferToRight.size()?
                                                              String.format(" and %d ranges from", transferToRight.size()) : "",
                                       dst);
        logger.info("{} {}", previewKind.logPrefix(desc.sessionId), message);
        Tracing.traceRepair(message);

        createStreamPlan(dst, preferred, toRequest, toTransfer).execute();
    }

    public void handleStreamEvent(StreamEvent event)
    {
        if (state == null)
            return;
        switch (event.eventType)
        {
            case STREAM_PREPARED:
                StreamEvent.SessionPreparedEvent spe = (StreamEvent.SessionPreparedEvent) event;
                state.trace("Streaming session with {} prepared", spe.session.peer);
                break;
            case STREAM_COMPLETE:
                StreamEvent.SessionCompleteEvent sce = (StreamEvent.SessionCompleteEvent) event;
                state.trace("Streaming session with {} {}", sce.peer, sce.success ? "completed successfully" : "failed");
                break;
            case FILE_PROGRESS:
                ProgressInfo pi = ((StreamEvent.ProgressEvent) event).progress;
                state.trace("{}/{} ({}%) {} idx:{}{}",
                            new Object[] { FBUtilities.prettyPrintMemory(pi.currentBytes),
                                           FBUtilities.prettyPrintMemory(pi.totalBytes),
                                           pi.currentBytes * 100 / pi.totalBytes,
                                           pi.direction == ProgressInfo.Direction.OUT ? "sent to" : "received from",
                                           pi.sessionIndex,
                                           pi.peer });
        }
    }

    public void onSuccess(StreamState result)
    {
        String message = String.format("Sync complete using session %s between %s and %s on %s", desc.sessionId, r1.endpoint, r2.endpoint, desc.columnFamily);
        logger.info("[repair #{}] {}", desc.sessionId, message);
        Tracing.traceRepair(message);
        set(stat.withSummaries(result.createSummaries()));
        finished();
    }

    public void onFailure(Throwable t)
    {
        setException(t);
        finished();
    }
}
