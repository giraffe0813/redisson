/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.redisson.MasterSlaveServersConfig;
import org.redisson.RedissonFuture;
import org.redisson.client.ReconnectListener;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.core.NodeType;
import org.redisson.core.RFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientConnectionsEntry {

    final Logger log = LoggerFactory.getLogger(getClass());

    private final Queue<RedisPubSubConnection> allSubscribeConnections = new ConcurrentLinkedQueue<RedisPubSubConnection>();
    private final Queue<RedisPubSubConnection> freeSubscribeConnections = new ConcurrentLinkedQueue<RedisPubSubConnection>();
    private final AtomicInteger freeSubscribeConnectionsCounter = new AtomicInteger();

    private final Queue<RedisConnection> freeConnections = new ConcurrentLinkedQueue<RedisConnection>();
    private final AtomicInteger freeConnectionsCounter = new AtomicInteger();

    public enum FreezeReason {MANAGER, RECONNECT, SYSTEM}

    private volatile boolean freezed;
    private FreezeReason freezeReason;
    final RedisClient client;

    private final NodeType nodeType;
    private ConnectionManager connectionManager;

    private final AtomicInteger failedAttempts = new AtomicInteger();

    public ClientConnectionsEntry(RedisClient client, int poolMinSize, int poolMaxSize, int subscribePoolMinSize, int subscribePoolMaxSize,
            ConnectionManager connectionManager, NodeType serverMode) {
        this.client = client;
        this.freeConnectionsCounter.set(poolMaxSize);
        this.connectionManager = connectionManager;
        this.nodeType = serverMode;
        this.freeSubscribeConnectionsCounter.set(subscribePoolMaxSize);

        if (subscribePoolMaxSize > 0) {
            connectionManager.getConnectionWatcher().add(subscribePoolMinSize, subscribePoolMaxSize, freeSubscribeConnections, freeSubscribeConnectionsCounter);
        }
        connectionManager.getConnectionWatcher().add(poolMinSize, poolMaxSize, freeConnections, freeConnectionsCounter);
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public void resetFailedAttempts() {
        failedAttempts.set(0);
    }

    public int getFailedAttempts() {
        return failedAttempts.get();
    }

    public int incFailedAttempts() {
        return failedAttempts.incrementAndGet();
    }

    public RedisClient getClient() {
        return client;
    }

    public boolean isFreezed() {
        return freezed;
    }

    public void setFreezeReason(FreezeReason freezeReason) {
        this.freezeReason = freezeReason;
    }

    public FreezeReason getFreezeReason() {
        return freezeReason;
    }

    public void setFreezed(boolean freezed) {
        this.freezed = freezed;
    }

    public int getFreeAmount() {
        return freeConnectionsCounter.get();
    }

    private boolean tryAcquire(AtomicInteger counter) {
        while (true) {
            int value = counter.get();
            if (value == 0) {
                return false;
            }
            if (counter.compareAndSet(value, value - 1)) {
                return true;
            }
        }
    }

    public boolean tryAcquireConnection() {
        return tryAcquire(freeConnectionsCounter);
    }

    public void releaseConnection() {
        freeConnectionsCounter.incrementAndGet();
    }

    public RedisConnection pollConnection() {
        return freeConnections.poll();
    }

    public void releaseConnection(RedisConnection connection) {
        connection.setLastUsageTime(System.currentTimeMillis());
        freeConnections.add(connection);
    }

    public RFuture<RedisConnection> connect() {
        RedissonFuture<RedisConnection> connectionFuture = new RedissonFuture<>();
        RFuture<RedisConnection> future = client.connectAsync();
        future.thenAccept(conn -> {
            log.debug("new connection created: {}", conn);
            addReconnectListener(connectionFuture, conn);
        }).exceptionally(cause -> {
            connectionFuture.completeExceptionally(cause);
            return null;
        });
        return connectionFuture;
    }

    private <T extends RedisConnection> void addReconnectListener(CompletableFuture<T> connectionFuture, T conn) {
        addFireEventListener(conn, connectionFuture);

        conn.setReconnectListener(new ReconnectListener() {
            @Override
            public void onReconnect(RedisConnection conn, CompletableFuture<RedisConnection> connectionFuture) {
                addFireEventListener(conn, connectionFuture);
            }
        });
    }

    private <T extends RedisConnection> void addFireEventListener(T conn, CompletableFuture<T> connectionFuture) {
        connectionManager.getConnectListener().onConnect(connectionFuture, conn, nodeType, connectionManager.getConfig());
        
        if (connectionFuture.isDone() && !connectionFuture.isCompletedExceptionally()) {
            connectionManager.getConnectionEventsHub().fireConnect(connectionFuture.getNow(null).getRedisClient().getAddr());
            return;
        }

        connectionFuture.thenAccept(c -> {
            connectionManager.getConnectionEventsHub().fireConnect(c.getRedisClient().getAddr());
        });
    }
    
    public MasterSlaveServersConfig getConfig() {
        return connectionManager.getConfig();
    }

    public RFuture<RedisPubSubConnection> connectPubSub() {
        RedissonFuture<RedisPubSubConnection> connectionFuture = new RedissonFuture<>();
        RFuture<RedisPubSubConnection> future = client.connectPubSubAsync();
        future.thenAccept(conn -> {
            log.debug("new pubsub connection created: {}", conn);
            
            addReconnectListener(connectionFuture, conn);
            allSubscribeConnections.add(conn);
        }).exceptionally(cause -> {
            connectionFuture.completeExceptionally(cause);
            return null;
        });
        return connectionFuture;
    }

    public Queue<RedisPubSubConnection> getAllSubscribeConnections() {
        return allSubscribeConnections;
    }

    public RedisPubSubConnection pollSubscribeConnection() {
        return freeSubscribeConnections.poll();
    }

    public void releaseSubscribeConnection(RedisPubSubConnection connection) {
        connection.setLastUsageTime(System.currentTimeMillis());
        freeSubscribeConnections.add(connection);
    }

    public boolean tryAcquireSubscribeConnection() {
        return tryAcquire(freeSubscribeConnectionsCounter);
    }

    public void releaseSubscribeConnection() {
        freeSubscribeConnectionsCounter.incrementAndGet();
    }

    public boolean freezeMaster(FreezeReason reason) {
        synchronized (this) {
            setFreezed(true);
            // only RECONNECT freeze reason could be replaced
            if (getFreezeReason() == null
                    || getFreezeReason() == FreezeReason.RECONNECT) {
                setFreezeReason(reason);
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "[freeSubscribeConnectionsAmount=" + freeSubscribeConnections.size()
                + ", freeSubscribeConnectionsCounter=" + freeSubscribeConnectionsCounter
                + ", freeConnectionsAmount=" + freeConnections.size() + ", freeConnectionsCounter="
                + freeConnectionsCounter + ", freezed=" + freezed + ", freezeReason=" + freezeReason
                + ", client=" + client + ", nodeType=" + nodeType + ", failedAttempts=" + failedAttempts
                + "]";
    }

}

