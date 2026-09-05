package com.sh.engine.processor.recorder.stream;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.constant.RecordConstant;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用多个短连接 Range 请求下载完整 VOD，并通过完成标记支持进程重启后续传。
 */
@Slf4j
public class RangeVodDownloader {
    private static final int CONCURRENCY = 4;
    private static final int CHUNK_SIZE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BUFFER_SIZE_BYTES = 64 * 1024;
    private static final int PROGRESS_STEP_PERCENT = 5;
    private static final long CALL_TIMEOUT_MINUTES = 3;
    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile(
            "^bytes\\s+(\\d+)-(\\d+)/(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient httpClient;

    public RangeVodDownloader() {
        this.httpClient = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * 下载远端文件到工作目录。成功文件保留在工作目录中，供后续本地 FFmpeg 读取。
     *
     * @param sourceUrl 带签名的远端下载地址，仅用于请求，不写入磁盘或日志
     * @param workDirectory 当前录像专属的下载工作目录
     * @return 完整下载后的本地文件
     */
    public File download(String sourceUrl, File workDirectory) {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM, "Range VOD source URL is empty");
        }
        if (workDirectory == null) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM, "Range VOD work directory is null");
        }

        try {
            RemoteMetadata metadata = probe(sourceUrl);
            RangeVodDownloadWorkspace workspace = RangeVodDownloadWorkspace.prepare(
                    workDirectory.toPath(), metadata.totalLength, CHUNK_SIZE_BYTES,
                    metadata.validator, metadata.sourceIdentity);
            downloadMissingChunks(sourceUrl, workspace, metadata);
            int totalChunks = chunkCount(metadata.totalLength);
            workspace.validate(metadata.totalLength, totalChunks);
            log.info("Range VOD download complete, bytes: {}, chunks: {}",
                    metadata.totalLength, totalChunks);
            return workspace.getSourceFile().toFile();
        } catch (IllegalArgumentException e) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM,
                    "Range VOD source URL is invalid", e);
        } catch (IOException e) {
            throw new StreamerRecordException(ErrorEnum.RECORD_ERROR,
                    "Range VOD download failed, workDirectory: " + workDirectory.getAbsolutePath(), e);
        }
    }

    /**
     * 本地 FFmpeg 成功后清理下载源文件和续传标记；不会递归删除未知文件。
     */
    public void cleanup(File workDirectory) {
        if (workDirectory == null || !workDirectory.exists()) {
            return;
        }
        try {
            RangeVodDownloadWorkspace.cleanup(workDirectory.toPath());
        } catch (IOException e) {
            throw new StreamerRecordException(ErrorEnum.RECORD_ERROR,
                    "Range VOD workspace cleanup failed: " + workDirectory.getAbsolutePath(), e);
        }
    }

    /**
     * 用一个字节的标准 Range 请求确认服务端支持分段下载，并取得源文件元信息。
     */
    private RemoteMetadata probe(String sourceUrl) throws IOException {
        Request request = requestBuilder(sourceUrl)
                .header("Range", "bytes=0-0")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() != 206) {
                throw new IOException("Range probe expected HTTP 206 but got " + response.code());
            }
            ContentRange contentRange = parseContentRange(response.header("Content-Range"));
            if (contentRange.start != 0 || contentRange.end != 0 || contentRange.totalLength <= 0) {
                throw new IOException("Range probe returned an invalid Content-Range");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Range probe returned an empty body");
            }
            try (InputStream inputStream = body.byteStream()) {
                if (inputStream.read() < 0) {
                    throw new IOException("Range probe body is empty");
                }
            }
            String validator = response.header("ETag");
            if (validator == null) {
                validator = response.header("Last-Modified", "");
            }
            return new RemoteMetadata(contentRange.totalLength, validator, sourceIdentity(sourceUrl));
        }
    }

    /**
     * 始终只保留四个在途任务，避免为超长视频创建无界任务队列。
     */
    private void downloadMissingChunks(String sourceUrl,
                                       RangeVodDownloadWorkspace workspace,
                                       RemoteMetadata metadata) throws IOException {
        List<RangeChunk> missingChunks = new ArrayList<>();
        long completedBytes = 0;
        int totalChunks = chunkCount(metadata.totalLength);
        for (int index = 0; index < totalChunks; index++) {
            RangeChunk chunk = chunk(index, metadata.totalLength);
            if (workspace.isChunkCompleted(chunk.index)) {
                completedBytes += chunk.length();
            } else {
                missingChunks.add(chunk);
            }
        }
        if (missingChunks.isEmpty()) {
            log.info("Range VOD download already complete, bytes: {}, chunks: {}",
                    metadata.totalLength, totalChunks);
            return;
        }

        ThreadPoolExecutor executor = newExecutor();
        CompletionService<RangeChunk> completionService = new ExecutorCompletionService<>(executor);
        List<Future<RangeChunk>> futures = new ArrayList<>();
        long startNanos = System.nanoTime();
        long downloadedBytes = 0;
        int nextLogPercent = Math.min(100,
                ((int) (completedBytes * 100.0 / metadata.totalLength)
                        / PROGRESS_STEP_PERCENT + 1) * PROGRESS_STEP_PERCENT);
        int nextIndex = 0;
        int inFlight = 0;
        try {
            while (nextIndex < missingChunks.size() && inFlight < CONCURRENCY) {
                futures.add(submit(completionService, sourceUrl, workspace, metadata,
                        missingChunks.get(nextIndex++)));
                inFlight++;
            }
            while (inFlight > 0) {
                RangeChunk completedChunk = takeCompleted(completionService);
                inFlight--;
                completedBytes += completedChunk.length();
                downloadedBytes += completedChunk.length();
                int percent = (int) Math.min(100,
                        completedBytes * 100.0 / metadata.totalLength);
                if (percent >= nextLogPercent || completedBytes == metadata.totalLength) {
                    logProgress(percent, completedBytes, downloadedBytes,
                            metadata.totalLength, startNanos);
                    nextLogPercent = Math.min(100,
                            (percent / PROGRESS_STEP_PERCENT + 1) * PROGRESS_STEP_PERCENT);
                }
                if (nextIndex < missingChunks.size()) {
                    futures.add(submit(completionService, sourceUrl, workspace, metadata,
                            missingChunks.get(nextIndex++)));
                    inFlight++;
                }
            }
        } finally {
            for (Future<RangeChunk> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
            httpClient.dispatcher().cancelAll();
            shutdownExecutor(executor);
        }
    }

    /**
     * 下载单个区间并在数据落盘后创建完成标记。每次重试都会重新请求完整区间。
     */
    private RangeChunk downloadChunk(String sourceUrl,
                                     RangeVodDownloadWorkspace workspace,
                                     RemoteMetadata metadata,
                                     RangeChunk chunk) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                downloadChunkOnce(sourceUrl, workspace.getSourceFile(), metadata, chunk);
                workspace.markChunkCompleted(chunk.index);
                return chunk;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                log.warn("Range VOD chunk retry, index: {}, attempt: {}/{}, reason: {}",
                        chunk.index, attempt, MAX_ATTEMPTS, e.getMessage());
                waitBeforeRetry(attempt);
            }
        }
        throw new IOException("Range chunk failed after retries, index: " + chunk.index, lastFailure);
    }

    /**
     * 校验响应区间并将响应体写入目标文件的准确字节偏移。
     */
    private void downloadChunkOnce(String sourceUrl,
                                   Path sourceFile,
                                   RemoteMetadata metadata,
                                   RangeChunk chunk) throws IOException {
        Request request = requestBuilder(sourceUrl)
                .header("Range", "bytes=" + chunk.start + "-" + chunk.end)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.code() != 206) {
                throw new IOException("Expected HTTP 206 but got " + response.code());
            }
            ContentRange contentRange = parseContentRange(response.header("Content-Range"));
            if (contentRange.start != chunk.start || contentRange.end != chunk.end
                    || contentRange.totalLength != metadata.totalLength) {
                throw new IOException("Response Content-Range does not match requested chunk");
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Range response body is empty");
            }
            writeChunk(body, sourceFile, chunk);
        }
    }

    /**
     * 写入完整区间并同步到磁盘；响应不足或超长都视为失败。
     */
    private void writeChunk(ResponseBody body, Path sourceFile, RangeChunk chunk) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        long written = 0;
        try (InputStream inputStream = body.byteStream();
             RandomAccessFile target = new RandomAccessFile(sourceFile.toFile(), "rw")) {
            target.seek(chunk.start);
            while (written < chunk.length()) {
                int readLength = (int) Math.min(buffer.length, chunk.length() - written);
                int read = inputStream.read(buffer, 0, readLength);
                if (read < 0) {
                    throw new IOException("Range response ended early, bytes: " + written);
                }
                target.write(buffer, 0, read);
                written += read;
            }
            if (inputStream.read() >= 0) {
                throw new IOException("Range response exceeded requested length");
            }
            target.getFD().sync();
        }
    }

    private Request.Builder requestBuilder(String sourceUrl) {
        return new Request.Builder()
                .url(sourceUrl)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .header("User-Agent", RecordConstant.USER_AGENT)
                .get();
    }

    private Future<RangeChunk> submit(CompletionService<RangeChunk> completionService,
                                      String sourceUrl,
                                      RangeVodDownloadWorkspace workspace,
                                      RemoteMetadata metadata,
                                      RangeChunk chunk) {
        return completionService.submit(() -> downloadChunk(sourceUrl, workspace, metadata, chunk));
    }

    private RangeChunk takeCompleted(CompletionService<RangeChunk> completionService) throws IOException {
        try {
            return completionService.take().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Range VOD download interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Range VOD chunk execution failed", cause);
        }
    }

    private ThreadPoolExecutor newExecutor() {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("range-vod-download-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                CONCURRENCY,
                CONCURRENCY,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONCURRENCY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void shutdownExecutor(ThreadPoolExecutor executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Range VOD executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing Range VOD executor");
        }
    }

    private void waitBeforeRetry(int attempt) throws IOException {
        try {
            TimeUnit.SECONDS.sleep(attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Range VOD retry interrupted", e);
        }
    }

    private void logProgress(int percent,
                             long completedBytes,
                             long downloadedBytes,
                             long totalBytes,
                             long startNanos) {
        long elapsedNanos = Math.max(1, System.nanoTime() - startNanos);
        long bytesPerSecond = downloadedBytes * TimeUnit.SECONDS.toNanos(1) / elapsedNanos;
        log.info("Range VOD progress: {}%, downloaded: {}/{}, average: {} B/s",
                percent, completedBytes, totalBytes, bytesPerSecond);
    }

    private int chunkCount(long totalLength) throws IOException {
        long count = (totalLength + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES;
        if (count > Integer.MAX_VALUE) {
            throw new IOException("Range VOD contains too many chunks: " + count);
        }
        return (int) count;
    }

    private RangeChunk chunk(int index, long totalLength) {
        long start = (long) index * CHUNK_SIZE_BYTES;
        long end = Math.min(totalLength - 1, start + CHUNK_SIZE_BYTES - 1L);
        return new RangeChunk(index, start, end);
    }

    private ContentRange parseContentRange(String header) throws IOException {
        if (header == null) {
            throw new IOException("Range response is missing Content-Range");
        }
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(header.trim());
        if (!matcher.matches()) {
            throw new IOException("Invalid Content-Range response");
        }
        try {
            return new ContentRange(
                    Long.parseLong(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)));
        } catch (NumberFormatException e) {
            throw new IOException("Content-Range contains an invalid number", e);
        }
    }

    private String sourceIdentity(String sourceUrl) throws IOException {
        URL parsedUrl = new URL(sourceUrl);
        String identity = parsedUrl.getProtocol() + "://" + parsedUrl.getHost()
                + (parsedUrl.getPort() < 0 ? "" : ":" + parsedUrl.getPort())
                + parsedUrl.getPath();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static final class RemoteMetadata {
        private final long totalLength;
        private final String validator;
        private final String sourceIdentity;

        private RemoteMetadata(long totalLength, String validator, String sourceIdentity) {
            this.totalLength = totalLength;
            this.validator = validator == null ? "" : validator;
            this.sourceIdentity = sourceIdentity;
        }
    }

    private static final class RangeChunk {
        private final int index;
        private final long start;
        private final long end;

        private RangeChunk(int index, long start, long end) {
            this.index = index;
            this.start = start;
            this.end = end;
        }

        private long length() {
            return end - start + 1;
        }
    }

    private static final class ContentRange {
        private final long start;
        private final long end;
        private final long totalLength;

        private ContentRange(long start, long end, long totalLength) {
            this.start = start;
            this.end = end;
            this.totalLength = totalLength;
        }
    }
}
