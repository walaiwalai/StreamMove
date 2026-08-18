package com.sh.engine.processor.checker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StreamrecorderIOLoginManagerTest {

    @Test
    public void onlyOneThreadLogsInWhenCookiesExpireConcurrently() throws Exception {
        AtomicInteger loginCount = new AtomicInteger();
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch releaseLogin = new CountDownLatch(1);
        StreamrecorderIOLoginManager loginManager = new StreamrecorderIOLoginManager() {
            @Override
            protected void doLogin() {
                loginCount.incrementAndGet();
                loginStarted.countDown();
                try {
                    if (!releaseLogin.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to finish test login");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                getCookieJar().loadCookiesFromString("session=fresh", "streamrecorder.io");
            }
        };
        setAccountSavePath(loginManager);
        loginManager.getCookieJar().loadCookiesFromString("session=expired", "streamrecorder.io");

        int threadCount = 8;
        CountDownLatch firstRequests = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return loginManager.executeWithCookies(cookieString -> {
                        if (cookieString.contains("expired")) {
                            firstRequests.countDown();
                            try {
                                if (!firstRequests.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Not all requests used the expired cookies");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            throw new RuntimeException("cookies expired");
                        }
                        return cookieString;
                    });
                }));
            }

            start.countDown();
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1, loginCount.get());

            releaseLogin.countDown();
            for (Future<String> future : futures) {
                assertEquals("session=fresh", future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, loginCount.get());
        } finally {
            releaseLogin.countDown();
            executor.shutdownNow();
            loginManager.getCookieJar().clearAllCookies();
        }
    }

    private static void setAccountSavePath(StreamrecorderIOLoginManager loginManager) throws Exception {
        Field field = StreamrecorderIOLoginManager.class.getDeclaredField("accountSavePath");
        field.setAccessible(true);
        field.set(loginManager, "target/streamrecorder-login-manager-test");
    }
}
