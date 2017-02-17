/**
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
package org.apache.bookkeeper.meta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.proto.BookkeeperInternalCallbacks.GenericCallback;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ZooKeeper based ledger id generator class, which using EPHEMERAL_SEQUENTIAL
 * with <i>(ledgerIdGenPath)/HOB-[high-32-bits]/ID-</i> prefix to generate ledger id. Note
 * zookeeper sequential counter has a format of %10d -- that is 10 digits with 0
 * (zero) padding, i.e. "&lt;path&gt;0000000001", so ledger id space would be
 * fundamentally limited to 9 billion. In practice, the id generated by zookeeper
 * is only 32 bits, so the limit is much lower than 9 billion.
 *
 * In order to support the full range of the long ledgerId, once ledgerIds reach Integer.MAX_INT,
 * a new system is employed. The 32 most significant bits of the ledger ID are taken and turned into
 * a directory prefixed with <i>HOB-</i> under <i>(ledgerIdGenPath)</i>
 *
 * Under this <i>HOB-</i> directory, zookeeper is used to continue generating EPHEMERAL_SEQUENTIAL ids
 * which constitute the lower 32-bits of the ledgerId. Once the <i>HOB-</i> directory runs out of available
 * ids, the process is repeated. The higher bits are incremented, a new <i>HOB-</i> directory is created, and
 * zookeeper generates sequential ids underneath it.
 *
 * The reason for treating ids which are less than Integer.MAX_INT differently is to maintain backwards
 * compatibility. This is a drop-in replacement for ZkLedgerIdGenerator.
 */
public class LongZkLedgerIdGenerator implements LedgerIdGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(LongZkLedgerIdGenerator.class);
    private ZooKeeper zk;
    private String ledgerIdGenPath;
    private ZkLedgerIdGenerator shortIdGen;
    private List<String> highOrderDirectories;
    private HighOrderLedgerIdGenPathStatus ledgerIdGenPathStatus;

    private enum HighOrderLedgerIdGenPathStatus {
        UNKNOWN,
        PRESENT,
        NOT_PRESENT
    };

    public LongZkLedgerIdGenerator(ZooKeeper zk, String ledgersPath, String idGenZnodeName, ZkLedgerIdGenerator shortIdGen) {
        this.zk = zk;
        if (StringUtils.isBlank(idGenZnodeName)) {
            this.ledgerIdGenPath = ledgersPath;
        } else {
            this.ledgerIdGenPath = ledgersPath + "/" + idGenZnodeName;
        }
        this.shortIdGen = shortIdGen;
        highOrderDirectories = new ArrayList<String>();
        ledgerIdGenPathStatus = HighOrderLedgerIdGenPathStatus.UNKNOWN;
    }

    private void generateLongLedgerIdLowBits(final String ledgerPrefix, long highBits, final GenericCallback<Long> cb) throws KeeperException, InterruptedException, IOException {
        String highPath = ledgerPrefix + formatHalfId((int)highBits);
        ZkLedgerIdGenerator.generateLedgerIdImpl(new GenericCallback<Long>(){
            @Override
            public void operationComplete(int rc, Long result) {
                if(rc == BKException.Code.OK) {
                    assert((highBits & 0xFFFFFFFF00000000l) == 0);
                    assert((result & 0xFFFFFFFF00000000l) == 0);
                    cb.operationComplete(rc, (highBits << 32) | result);
                }
                else if(rc == BKException.Code.LedgerIdOverflowException) {
                    // Lower bits are full. Need to expand and create another HOB node.
                    try {
                        Long newHighBits = highBits + 1;
                        createHOBPathAndGenerateId(ledgerPrefix, newHighBits.intValue(), cb);
                    }
                    catch (KeeperException e) {
                        LOG.error("Failed to create long ledger ID path", e);
                        cb.operationComplete(BKException.Code.ZKException, null);
                    }
                    catch (InterruptedException e) {
                        LOG.error("Failed to create long ledger ID path", e);
                        cb.operationComplete(BKException.Code.InterruptedException, null);
                    } catch (IOException e) {
                        LOG.error("Failed to create long ledger ID path", e);
                        cb.operationComplete(BKException.Code.IllegalOpException, null);
                    }

                }
            }

        }, zk, ZkLedgerIdGenerator.createLedgerPrefix(highPath, null));
    }

    /**
     * Formats half an ID as 10-character 0-padded string
     * @param i - 32 bits of the ID to format
     * @return a 10-character 0-padded string.
     */
    private String formatHalfId(Integer i) {
        StringBuilder sb = new StringBuilder();
        try (Formatter fmt = new Formatter(sb, Locale.US);) {
            fmt.format("%010d", i);
            return sb.toString();
        }
    }

    private void createHOBPathAndGenerateId(String ledgerPrefix, int hob, final GenericCallback<Long> cb) throws KeeperException, InterruptedException, IOException {
        try {
            LOG.debug("Creating HOB path: {}", ledgerPrefix + formatHalfId(hob));
            zk.create(ledgerPrefix + formatHalfId(hob), new byte[0], Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
        catch(KeeperException.NodeExistsException e) {
            // It's fine if we lost a race to create the node (NodeExistsException).
            // All other exceptions should continue unwinding.
            LOG.debug("Tried to create High-order-bits node, but it already existed!", e);
        }
        // We just created a new HOB directory. Invalidate the directory cache
        invalidateDirectoryCache();
        generateLongLedgerId(cb); // Try again.
    }

    private void invalidateDirectoryCache() {
        highOrderDirectories = null;
    }

    private void generateLongLedgerId(final GenericCallback<Long> cb) throws KeeperException, InterruptedException, IOException {
        final String hobPrefix = "HOB-";
        final String ledgerPrefix = this.ledgerIdGenPath + "/" + hobPrefix;

        // Only pull the directories from zk if we don't have any cached.
        boolean refreshedDirectories = false;
        if(highOrderDirectories == null) {
            refreshedDirectories = true;
            highOrderDirectories = zk.getChildren(ledgerIdGenPath, false);
        }

        Optional<Long> largest = highOrderDirectories.stream()
            .map((t) -> {
                    try {
                        return Long.parseLong(t.replace(hobPrefix, ""));
                    }
                    catch(NumberFormatException e) {
                        return null;
                    }
                })
            .filter((t) -> t != null)
            .reduce(Math::max);

        // If we didn't get any valid IDs from the directory...
        if(!largest.isPresent()) {
            if(!refreshedDirectories) {
                // Our cache might be bad. Invalidate it and retry.
                invalidateDirectoryCache();
                generateLongLedgerId(cb); // Try again
            }
            else {
                // else, Start at HOB-0000000001;
                createHOBPathAndGenerateId(ledgerPrefix, 1, cb);
            }
        }
        else {
            // Found the largest.
            // Get the low-order bits.
            final Long highBits = largest.get();
            generateLongLedgerIdLowBits(ledgerPrefix, highBits, cb);
        }
    }

    private void createLongLedgerIdPathAndGenerateLongLedgerId(final GenericCallback<Long> cb, String createPath) {
        ZkUtils.asyncCreateFullPathOptimistic(zk, ledgerIdGenPath, new byte[0], Ids.OPEN_ACL_UNSAFE,
                                              CreateMode.PERSISTENT, new StringCallback() {
                                                      @Override
                                                      public void processResult(int rc, String path, Object ctx, String name) {
                                                          try {
                                                              setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus.PRESENT);
                                                              generateLongLedgerId(cb);
                                                          } catch (KeeperException e) {
                                                              LOG.error("Failed to create long ledger ID path", e);
                                                              setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus.UNKNOWN);
                                                              cb.operationComplete(BKException.Code.ZKException, null);
                                                          } catch (InterruptedException e) {
                                                              LOG.error("Failed to create long ledger ID path", e);
                                                              setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus.UNKNOWN);
                                                              cb.operationComplete(BKException.Code.InterruptedException, null);
                                                          } catch (IOException e) {
                                                              LOG.error("Failed to create long ledger ID path", e);
                                                              setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus.UNKNOWN);
                                                              cb.operationComplete(BKException.Code.IllegalOpException, null);
                                                          }
                                                      }
                                                  }, null);
    }

    public void invalidateLedgerIdGenPathStatus() {
        setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus.UNKNOWN);
    }

    synchronized private void setLedgerIdGenPathStatus(HighOrderLedgerIdGenPathStatus status) {
        ledgerIdGenPathStatus = status;
    }

    synchronized public boolean ledgerIdGenPathPresent(ZooKeeper zk) throws KeeperException, InterruptedException {
        switch(ledgerIdGenPathStatus) {
        case UNKNOWN:
            if(zk.exists(ledgerIdGenPath, false) != null) {
                ledgerIdGenPathStatus = HighOrderLedgerIdGenPathStatus.PRESENT;
                return true;
            }
            else {
                ledgerIdGenPathStatus = HighOrderLedgerIdGenPathStatus.NOT_PRESENT;
                return false;
            }
        case PRESENT:
            return true;
        case NOT_PRESENT:
            return false;
        default:
            return false;
        }
    }

    @Override
    public void generateLedgerId(final GenericCallback<Long> cb) {
        try {
            if(!ledgerIdGenPathPresent(zk)) {
                // We've not moved onto 63-bit ledgers yet.
                shortIdGen.generateLedgerId(new GenericCallback<Long>(){
                        @Override
                        public void operationComplete(int rc, Long result) {
                            if(rc == BKException.Code.LedgerIdOverflowException) {
                                // 31-bit IDs overflowed. Start using 63-bit ids.
                                createLongLedgerIdPathAndGenerateLongLedgerId(cb, ledgerIdGenPath);
                            }
                            else {
                                // 31-bit Generation worked OK, or had some other
                                // error that we will pass on.
                                cb.operationComplete(rc, result);
                            }
                        }
                    });
            }
            else {
                // We've already started generating 63-bit ledger IDs.
                // Keep doing that.
                generateLongLedgerId(cb);
            }
        } catch (KeeperException e) {
            LOG.error("Failed to create long ledger ID path", e);
            cb.operationComplete(BKException.Code.ZKException, null);
        }
        catch (InterruptedException e) {
            LOG.error("Failed to create long ledger ID path", e);
            cb.operationComplete(BKException.Code.InterruptedException, null);
        }
        catch (IOException e) {
            LOG.error("Failed to create long ledger ID path", e);
            cb.operationComplete(BKException.Code.IllegalOpException, null);
        }
    }

    @Override
    public void close() throws IOException {
        shortIdGen.close();
    }

}
