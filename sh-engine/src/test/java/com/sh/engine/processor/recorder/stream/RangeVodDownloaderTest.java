package com.sh.engine.processor.recorder.stream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RangeVodDownloaderTest {
    private static final long CHUNK_SIZE = 32L * 1024 * 1024;
    private static final long CONTENT_LENGTH = CHUNK_SIZE * 2 + 12345;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private HttpServer server;
    private ThreadPoolExecutor serverExecutor;
    private RangeHandler rangeHandler;
    private String sourceUrl;

    @Before
    public void setUp() throws IOException {
        rangeHandler = new RangeHandler(CONTENT_LENGTH);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", rangeHandler);
        serverExecutor = newServerExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        sourceUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                + "/video.mp4?signature=secret";
    }

    @After
    public void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void downloadsConcurrentRangesAndResumesOnlyMissingChunk() throws Exception {
        File workDirectory = temporaryFolder.newFolder("range-download");
        RangeVodDownloader downloader = new RangeVodDownloader();

        File sourceFile = downloader.download(sourceUrl, workDirectory);

        assertEquals(CONTENT_LENGTH, sourceFile.length());
        assertTrue(rangeHandler.maxConcurrentRequests.get() >= 2);
        assertContentAt(sourceFile, 0);
        assertContentAt(sourceFile, CHUNK_SIZE - 1);
        assertContentAt(sourceFile, CHUNK_SIZE);
        assertContentAt(sourceFile, CONTENT_LENGTH - 1);

        Path completedDirectory = workDirectory.toPath().resolve("completed");
        Files.delete(completedDirectory.resolve("00000001.done"));
        try (RandomAccessFile target = new RandomAccessFile(sourceFile, "rw")) {
            target.seek(CHUNK_SIZE);
            target.write(0);
        }
        rangeHandler.chunkRequests.set(0);

        downloader.download(sourceUrl.replace("secret", "refreshed"), workDirectory);

        assertEquals(1, rangeHandler.chunkRequests.get());
        assertContentAt(sourceFile, CHUNK_SIZE);
        downloader.cleanup(workDirectory);
        assertFalse(workDirectory.exists());
    }

    private ThreadPoolExecutor newServerExecutor() {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("range-vod-test-server-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                4,
                4,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void assertContentAt(File sourceFile, long position) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(sourceFile, "r")) {
            input.seek(position);
            assertEquals(expectedByte(position), input.readUnsignedByte());
        }
    }

    private static int expectedByte(long position) {
        return (int) (position % 251);
    }

    private static final class RangeHandler implements HttpHandler {
        private static final int RESPONSE_BUFFER_SIZE = 64 * 1024;

        private final long contentLength;
        private final CountDownLatch concurrentRequestLatch = new CountDownLatch(2);
        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maxConcurrentRequests = new AtomicInteger();
        private final AtomicInteger chunkRequests = new AtomicInteger();

        private RangeHandler(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long[] range = parseRange(exchange.getRequestHeaders().getFirst("Range"));
            boolean probe = range[0] == 0 && range[1] == 0;
            if (!probe) {
                chunkRequests.incrementAndGet();
                int active = activeRequests.incrementAndGet();
                updateMaximum(active);
                concurrentRequestLatch.countDown();
                awaitConcurrentRequests();
            }
            try {
                writeResponse(exchange, range[0], range[1]);
            } finally {
                if (!probe) {
                    activeRequests.decrementAndGet();
                }
            }
        }

        private void writeResponse(HttpExchange exchange, long start, long end) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Range", "bytes " + start + "-" + end + "/" + contentLength);
            headers.add("ETag", "test-etag");
            long responseLength = end - start + 1;
            exchange.sendResponseHeaders(206, responseLength);
            byte[] buffer = new byte[RESPONSE_BUFFER_SIZE];
            try (OutputStream outputStream = exchange.getResponseBody()) {
                long position = start;
                while (position <= end) {
                    int length = (int) Math.min(buffer.length, end - position + 1);
                    for (int index = 0; index < length; index++) {
                        buffer[index] = (byte) expectedByte(position + index);
                    }
                    outputStream.write(buffer, 0, length);
                    position += length;
                }
            }
        }

        private long[] parseRange(String rangeHeader) throws IOException {
            if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
                throw new IOException("Missing Range header");
            }
            String[] values = rangeHeader.substring("bytes=".length()).split("-", 2);
            return new long[]{Long.parseLong(values[0]), Long.parseLong(values[1])};
        }

        private void updateMaximum(int active) {
            int currentMaximum;
            do {
                currentMaximum = maxConcurrentRequests.get();
                if (active <= currentMaximum) {
                    return;
                }
            } while (!maxConcurrentRequests.compareAndSet(currentMaximum, active));
        }

        private void awaitConcurrentRequests() throws IOException {
            try {
                if (!concurrentRequestLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Concurrent requests did not start in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Test server interrupted", e);
            }
        }
    }
}
