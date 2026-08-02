package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.utils.VideoFileUtil;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Java HTTP implementation of the captured finder assistance multipart upload protocol. */
@Slf4j
public final class WechatVideoStorageClient {
    static final int CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int MAX_RETRY = 3;
    private static final String ORIGIN = "https://channels.weixin.qq.com";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final MediaType BINARY_MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private final WechatVideoWebSession.UploadContext uploadContext;
    private final OkHttpClient httpClient;

    public WechatVideoStorageClient(WechatVideoWebSession.UploadContext uploadContext) {
        this(uploadContext, new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build());
    }

    WechatVideoStorageClient(WechatVideoWebSession.UploadContext uploadContext,
                             OkHttpClient httpClient) {
        this.uploadContext = uploadContext;
        this.httpClient = httpClient;
    }

    public StorageUploadResult upload(File file, int fileType, String logicalFileName)
            throws Exception {
        if (file == null || !file.isFile() || file.length() <= 0) {
            throw new IllegalArgumentException("微信视频号上传文件不存在或为空: "
                    + (file == null ? "null" : file.getAbsolutePath()));
        }
        String taskId = UUID.randomUUID().toString();
        String arguments = buildArguments(uploadContext, fileType, logicalFileName,
                file.length(), taskId);
        int partCount = partCount(file.length());
        long startedAt = System.currentTimeMillis();
        String uploadId = initialize(file.length(), partCount, arguments);
        List<PartInfo> parts = uploadParts(file, uploadId, arguments, partCount);
        String transFlag = parts.get(parts.size() - 1).getTransFlag();
        String downloadUrl = complete(uploadId, arguments, transFlag, parts);
        long finishedAt = System.currentTimeMillis();
        return new StorageUploadResult(normalizeDownloadUrl(downloadUrl), taskId,
                startedAt / 1_000L, finishedAt / 1_000L, finishedAt - startedAt);
    }

    private String initialize(long fileSize, int partCount, String arguments) throws Exception {
        JSONObject body = buildInitializeBody(fileSize, partCount);
        Request request = requestBuilder(primaryHost() + "/applyuploaddfs", arguments, "null")
                .put(RequestBody.create(JSON_MEDIA_TYPE,
                        body.toJSONString().getBytes(StandardCharsets.UTF_8)))
                .build();
        JSONObject response = executeJson(request, "初始化微信视频号分片上传");
        String uploadId = response.getString("UploadID");
        if (StringUtils.isBlank(uploadId)) {
            throw new IllegalStateException("初始化微信视频号分片上传失败: 缺少 UploadID");
        }
        return uploadId;
    }

    private List<PartInfo> uploadParts(File file,
                                       String uploadId,
                                       String arguments,
                                       int partCount) throws Exception {
        int parallel = Math.min(4, partCount);
        ExecutorService executor = Executors.newFixedThreadPool(parallel, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("wechat-video-upload-" + thread.getId());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<PartInfo>> futures = new ArrayList<>();
            for (int index = 0; index < partCount; index++) {
                final int partIndex = index;
                futures.add(executor.submit(new Callable<PartInfo>() {
                    @Override
                    public PartInfo call() throws Exception {
                        long offset = (long) partIndex * CHUNK_SIZE;
                        int length = (int) Math.min(CHUNK_SIZE, file.length() - offset);
                        byte[] bytes = VideoFileUtil.fetchBlock(file, offset, length);
                        PartInfo result = uploadPart(uploadId, arguments, partIndex + 1, bytes);
                        log.info("wechat channels upload progress: {}/{}, file: {}",
                                partIndex + 1, partCount, file.getAbsolutePath());
                        return result;
                    }
                }));
            }
            List<PartInfo> result = new ArrayList<>();
            for (Future<PartInfo> future : futures) {
                try {
                    result.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new IllegalStateException("微信视频号分片上传失败", cause);
                }
            }
            Collections.sort(result, Comparator.comparingInt(PartInfo::getPartNumber));
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private PartInfo uploadPart(String uploadId,
                                String arguments,
                                int partNumber,
                                byte[] bytes) throws Exception {
        String contentMd5 = DigestUtils.md5Hex(bytes);
        Exception lastError = null;
        List<String> hosts = uploadHosts();
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            String host = hosts.get((partNumber + attempt - 2) % hosts.size());
            HttpUrl url = HttpUrl.parse(host + "/uploadpartdfs").newBuilder()
                    .addQueryParameter("PartNumber", String.valueOf(partNumber))
                    .addQueryParameter("UploadID", uploadId)
                    .addQueryParameter("QuickUpload", "2")
                    .build();
            Request request = requestBuilder(url.toString(), arguments, contentMd5)
                    .put(RequestBody.create(BINARY_MEDIA_TYPE, bytes))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.code() + " "
                            + truncate(responseBody, 500));
                }
                String eTag = response.header("ETag");
                String transFlag = response.header("TransFlag");
                if (StringUtils.isAnyBlank(eTag, transFlag)) {
                    throw new IllegalStateException("响应缺少 ETag/TransFlag");
                }
                return new PartInfo(partNumber, eTag, transFlag);
            } catch (Exception e) {
                lastError = e;
                log.warn("wechat channels part upload failed, part: {}, attempt: {}/{}",
                        partNumber, attempt, MAX_RETRY, e);
            }
        }
        throw new IllegalStateException("微信视频号分片上传失败: part=" + partNumber, lastError);
    }

    private String complete(String uploadId,
                            String arguments,
                            String transFlag,
                            List<PartInfo> parts) throws Exception {
        JSONObject body = new JSONObject(true);
        body.put("TransFlag", transFlag);
        JSONArray partInfo = new JSONArray();
        for (PartInfo part : parts) {
            JSONObject item = new JSONObject(true);
            item.put("PartNumber", part.partNumber);
            item.put("ETag", part.eTag);
            partInfo.add(item);
        }
        body.put("PartInfo", partInfo);
        HttpUrl url = HttpUrl.parse(primaryHost() + "/completepartuploaddfs").newBuilder()
                .addQueryParameter("UploadID", uploadId)
                .build();
        Request request = requestBuilder(url.toString(), arguments, "null")
                .post(RequestBody.create(JSON_MEDIA_TYPE,
                        body.toJSONString().getBytes(StandardCharsets.UTF_8)))
                .build();
        JSONObject response = executeJson(request, "完成微信视频号分片上传");
        String downloadUrl = response.getString("DownloadURL");
        if (StringUtils.isBlank(downloadUrl)) {
            throw new IllegalStateException("完成微信视频号分片上传失败: 缺少 DownloadURL");
        }
        return downloadUrl;
    }

    private Request.Builder requestBuilder(String url, String arguments, String contentMd5) {
        return new Request.Builder().url(url)
                .header("Authorization", uploadContext.getAuthKey())
                .header("X-Arguments", arguments)
                .header("Content-MD5", contentMd5)
                .header("Origin", ORIGIN)
                .header("Referer", ORIGIN + "/");
    }

    private JSONObject executeJson(Request request, String operation) throws Exception {
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IllegalStateException(operation + "失败: HTTP " + response.code()
                        + " " + truncate(responseBody, 500));
            }
            JSONObject result = JSONObject.parseObject(responseBody);
            if (result == null) {
                throw new IllegalStateException(operation + "返回了空 JSON");
            }
            return result;
        }
    }

    private String primaryHost() {
        return toHttpsHost(uploadContext.getCdnHost());
    }

    private List<String> uploadHosts() {
        List<String> result = new ArrayList<>();
        if (uploadContext.getCdnHostList() != null) {
            for (String host : uploadContext.getCdnHostList()) {
                if (StringUtils.isNotBlank(host)) {
                    result.add(toHttpsHost(host));
                }
            }
        }
        if (result.isEmpty()) {
            result.add(primaryHost());
        }
        return result;
    }

    static JSONObject buildInitializeBody(long fileSize, int partCount) {
        JSONObject body = new JSONObject(true);
        body.put("BlockSum", partCount);
        JSONArray ends = new JSONArray();
        for (int index = 1; index <= partCount; index++) {
            ends.add(Math.min(fileSize, (long) index * CHUNK_SIZE));
        }
        body.put("BlockPartLength", ends);
        return body;
    }

    static String buildArguments(WechatVideoWebSession.UploadContext context,
                                 int fileType,
                                 String logicalFileName,
                                 long fileSize,
                                 String taskId) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("apptype", String.valueOf(context.getAppType()));
        values.put("filetype", String.valueOf(fileType));
        values.put("weixinnum", context.getUin());
        values.put("filekey", encodeURIComponent(logicalFileName));
        values.put("filesize", String.valueOf(fileSize));
        values.put("taskid", taskId);
        values.put("scene", String.valueOf(context.getScene()));
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> value : values.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(value.getKey()).append('=').append(value.getValue());
        }
        return result.toString();
    }

    static int partCount(long fileSize) {
        return (int) Math.max(1L, (fileSize + CHUNK_SIZE - 1L) / CHUNK_SIZE);
    }

    private static String encodeURIComponent(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    private static String toHttpsHost(String host) {
        String normalized = StringUtils.trimToEmpty(host);
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return normalized.replaceFirst("^http://", "https://");
        }
        return "https://" + normalized;
    }

    private static String normalizeDownloadUrl(String url) {
        if (url.startsWith("http://wxapp.tc.qq.com")) {
            return "https://finder.video.qq.com" + url.substring("http://wxapp.tc.qq.com".length());
        }
        return url;
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength
                ? StringUtils.defaultString(value) : value.substring(0, maxLength);
    }

    @Value
    private static class PartInfo {
        int partNumber;
        String eTag;
        String transFlag;
    }

    @Value
    public static class StorageUploadResult {
        String downloadUrl;
        String taskId;
        long uploadStartEpochSecond;
        long uploadEndEpochSecond;
        long uploadCostMillis;
    }
}
