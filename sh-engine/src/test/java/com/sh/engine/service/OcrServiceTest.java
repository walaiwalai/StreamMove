package com.sh.engine.service;


import com.sh.engine.model.highlight.core.OcrTextDetection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class OcrServiceTest {

    @Test
    public void sharedOcrUsesExistingMultipartProtocolAndParsesBoxes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            OcrService service = new OcrService();
            set(service, "host", "127.0.0.1"); set(service, "port", String.valueOf(server.getPort())); set(service, "token", "test-token");
            server.enqueue(new MockResponse().setBody("[{\"text\":\"100\",\"score\":0.9,\"boxes\":[1,2,3,4]}]"));
            List<OcrTextDetection> result = service.recognize(new byte[]{1,2,3}, "frame.jpg");
            assertEquals("100", result.get(0).getText()); assertEquals(4, result.get(0).getBoxes().size());
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/ocrDet", request.getPath()); assertEquals("Bearer test-token", request.getHeader("Authorization"));
            assertTrue(request.getBody().readUtf8().contains("name=\"image\"; filename=\"frame.jpg\""));
        }
    }

    private void set(Object bean, String name, Object value) throws Exception {
        Field field = bean.getClass().getDeclaredField(name); field.setAccessible(true); field.set(bean, value);
    }

    @Test
    public void concurrentHttpCallsStayWithinTwoAndFailuresReleaseCapacity() throws Exception {
        java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch thirdEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    int index = calls.incrementAndGet();
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try {
                        if (index <= 2) {
                            entered.countDown();
                            release.await(3, TimeUnit.SECONDS);
                        } else {
                            thirdEntered.countDown();
                        }
                        return index == 1 ? new MockResponse().setResponseCode(503)
                                : new MockResponse().setBody("[]");
                    } finally { active.decrementAndGet(); }
                }
            });
            server.start();
            OcrService service = new OcrService();
            set(service, "host", "127.0.0.1");
            set(service, "port", String.valueOf(server.getPort()));
            java.util.List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                jobs.add(callers.submit(() -> service.recognize(new byte[]{1}, "frame.jpg")));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(thirdEntered.await(150, TimeUnit.MILLISECONDS));
            release.countDown();
            int failures = 0;
            for (java.util.concurrent.Future<?> job : jobs) {
                try { job.get(3, TimeUnit.SECONDS); }
                catch (java.util.concurrent.ExecutionException expected) { failures++; }
            }
            assertEquals(1, failures);
            assertEquals(3, calls.get());
            assertEquals(2, maximum.get());
        } finally { release.countDown(); callers.shutdownNow(); }
    }
}
