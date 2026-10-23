package com.sh.config.manager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatusManagerTest {
    @Test
    public void concurrentRecordingAdmissionsDoNotExceedLimit() throws Exception {
        StatusManager statusManager = new StatusManager();
        int taskCount = 12;
        int limit = 3;
        int maxRecordingCount = statusManager.count() + limit;
        String prefix = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                String name = prefix + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return statusManager.tryAddRoomPathStatus(name, name, maxRecordingCount);
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertEquals(limit, admitted);
        } finally {
            start.countDown();
            executor.shutdownNow();
            for (int i = 0; i < taskCount; i++) {
                statusManager.deleteRoomPathStatus(prefix + i);
            }
        }
    }

    @Test
    public void sameStreamerCannotOverwriteRecordingStatus() {
        StatusManager statusManager = new StatusManager();
        String name = UUID.randomUUID().toString();
        int maxRecordingCount = statusManager.count() + 2;

        try {
            assertTrue(statusManager.tryAddRoomPathStatus("first", name, maxRecordingCount));
            assertFalse(statusManager.tryAddRoomPathStatus("second", name, maxRecordingCount));
            assertEquals("first", statusManager.getRecordingMap().get(name));
        } finally {
            statusManager.deleteRoomPathStatus(name);
        }
    }
}
