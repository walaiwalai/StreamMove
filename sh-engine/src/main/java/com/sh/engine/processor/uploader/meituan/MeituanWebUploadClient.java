package com.sh.engine.processor.uploader.meituan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.processor.uploader.meta.MeituanWorkMetaData;
import com.sh.message.service.MsgSendService;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java HTTP implementation of the captured Meituan creator video upload chain. */
@Slf4j
public final class MeituanWebUploadClient implements AutoCloseable {
    private static final String CONTENTS_ORIGIN = "https://contents.meituan.com";
    private static final String S3_ORIGIN = "https://s3plus.sankuai.com";
    private static final String S3_BUCKET = "tmp-mtvideo";
    private static final String COVER_ORIGIN = "https://pic-up.meituan.com";
    private static final String VIDEO_CONTENT_TYPE = "video/mp4";
    private static final long PART_SIZE = 100L * 1024L * 1024L;
    private static final int MAX_RETRY = 3;
    private static final List<String> REPLAY_HEADER_BLOCKLIST = Arrays.asList(
            "host", "content-length", "connection", "accept-encoding", "transfer-encoding"
    );

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build();
    private final MeituanWebSession webSession;

    public MeituanWebUploadClient(File storageStateFile) {
        this(storageStateFile, null);
    }

    public MeituanWebUploadClient(File storageStateFile, MsgSendService msgSendService) {
        this.webSession = new MeituanWebSession(storageStateFile, msgSendService);
    }

    public UploadResult upload(File videoFile,
                               File coverFile,
                               MeituanWorkMetaData metadata) throws Exception {
        if (videoFile == null || !videoFile.isFile()) {
            throw new IllegalArgumentException("美团视频文件不存在: "
                    + (videoFile == null ? "null" : videoFile.getAbsolutePath()));
        }
        if (coverFile == null || !coverFile.isFile()) {
            throw new IllegalArgumentException("美团首帧封面文件不存在: "
                    + (coverFile == null ? "null" : coverFile.getAbsolutePath()));
        }

        MeituanWebSession.CreatorProfile profile = webSession.getCreatorProfile();
        log.info("meituan creator session ready, creatorId: {}, authorName: {}",
                profile.getCreatorId(), profile.getAuthorName());
        VideoInfo videoInfo = detectVideoInfo(videoFile);
        String videoKey = allocateVideoKey();
        String coverKey = uploadCover(coverFile, profile.getCreatorId());

        log.info("begin meituan S3 multipart upload, video: {}, key: {}, size: {}",
                videoFile.getAbsolutePath(), videoKey, videoFile.length());
        uploadVideo(videoFile, videoKey);
        checkVideoContent(videoKey);
        String videoLink = completeVideo(videoKey);
        waitForContentPreCheck(videoKey);

        JSONObject publishBody = buildPublishBody(metadata, profile, videoInfo, videoKey,
                videoLink, coverKey);
        JSONObject publishResult = executeApiJson("POST", "/api/author/talent/video",
                publishBody.toJSONString(), "application/json", "发布美团视频");
        requireSuccess(publishResult, "发布美团视频");
        log.info("meituan publish response accepted, key: {}, response: {}", videoKey,
                truncate(publishResult.toJSONString(), 1_000));
        String contentId = StringUtils.defaultIfBlank(publishResult.getString("data"), videoKey);
        return new UploadResult(contentId, videoKey, coverKey);
    }

    @Override
    public void close() {
        webSession.close();
    }

    private String allocateVideoKey() throws IOException {
        JSONObject body = orderedObject();
        body.put("extension", "mp4");
        JSONObject response = executeApiJson("POST", "/api/author/videoUploadV2/filename",
                body.toJSONString(), "application/json", "分配美团视频文件名");
        requireSuccess(response, "分配美团视频文件名");
        String key = response.getString("data");
        if (StringUtils.isBlank(key)) {
            throw new IllegalStateException("分配美团视频文件名失败: data 为空");
        }
        return key;
    }

    private void uploadVideo(File videoFile, String key) throws Exception {
        String uploadId = initiateMultipartUpload(key);
        List<UploadedPart> parts = new ArrayList<>();
        long offset = 0;
        int partNumber = 1;
        while (offset < videoFile.length()) {
            long length = Math.min(PART_SIZE, videoFile.length() - offset);
            parts.add(uploadPart(videoFile, key, uploadId, partNumber, offset, length));
            offset += length;
            partNumber++;
        }
        completeMultipartUpload(key, uploadId, parts);
    }

    private String initiateMultipartUpload(String key) throws Exception {
        S3Credential credential = requestS3Credential("initMultiUpload", key, null, null);
        String url = s3ObjectUrl(key) + "?uploads";
        Map<String, String> headers = storageHeaders(credential);
        String body = "[object Object]";
        RawHttpResponse response = executeSignedRaw("POST", url, headers, body,
                VIDEO_CONTENT_TYPE, "初始化美团视频分片上传");
        String uploadId = extractXmlTag(response.body, "UploadId");
        if (StringUtils.isBlank(uploadId)) {
            throw new IllegalStateException("初始化美团视频分片上传失败: UploadId 为空");
        }
        return uploadId;
    }

    private UploadedPart uploadPart(File videoFile,
                                    String key,
                                    String uploadId,
                                    int partNumber,
                                    long offset,
                                    long length) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                S3Credential credential = requestS3Credential("uploadPart", key, uploadId,
                        partNumber);
                HttpUrl url = HttpUrl.parse(s3ObjectUrl(key)).newBuilder()
                        .addQueryParameter("partNumber", String.valueOf(partNumber))
                        .addQueryParameter("uploadId", uploadId)
                        .build();
                Request.Builder request = new Request.Builder().url(url)
                        .put(new FileSliceRequestBody(videoFile, offset, length,
                                MediaType.parse(VIDEO_CONTENT_TYPE)));
                addHeaders(request, storageHeaders(credential));
                addHeaders(request, webSession.getBrowserIdentityHeaders());
                try (Response response = httpClient.newCall(request.build()).execute()) {
                    if (!response.isSuccessful()) {
                        throw new IOException("HTTP " + response.code() + ": "
                                + truncate(response.body() == null ? "" : response.body().string(), 1_000));
                    }
                    String etag = response.header("ETag");
                    if (StringUtils.isBlank(etag)) {
                        throw new IOException("响应缺少 ETag");
                    }
                    log.info("meituan video part uploaded, part: {}, bytes: {}", partNumber,
                            length);
                    return new UploadedPart(partNumber, etag);
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("meituan video part upload failed, part: {}, attempt: {}/{}",
                        partNumber, attempt, MAX_RETRY, e);
            }
        }
        throw new IOException("美团视频分片上传失败, part=" + partNumber, lastError);
    }

    private void completeMultipartUpload(String key,
                                         String uploadId,
                                         List<UploadedPart> parts) throws Exception {
        S3Credential credential = requestS3Credential("completeMultipartUpload", key,
                uploadId, null);
        HttpUrl url = HttpUrl.parse(s3ObjectUrl(key)).newBuilder()
                .addQueryParameter("uploadId", uploadId)
                .build();
        String xml = buildCompleteMultipartXml(parts);
        executeSignedRaw("POST", url.toString(), storageHeaders(credential), xml,
                VIDEO_CONTENT_TYPE, "完成美团视频分片上传");
    }

    private S3Credential requestS3Credential(String type,
                                              String key,
                                              String uploadId,
                                              Integer partNumber) throws IOException {
        JSONObject body = orderedObject();
        body.put("type", type);
        body.put("sign", true);
        body.put("key", key);
        body.put("contentType", VIDEO_CONTENT_TYPE);
        if (StringUtils.isNotBlank(uploadId)) {
            body.put("uploadId", uploadId);
        }
        if (partNumber != null) {
            body.put("partNumber", partNumber);
        }
        JSONObject response = executeApiJson("POST",
                "/api/author/videoMultiUploadV2/signature", body.toJSONString(),
                "application/json", "获取美团 S3 上传签名");
        requireSuccess(response, "获取美团 S3 上传签名");
        JSONObject data = response.getJSONObject("data");
        if (data == null || StringUtils.isAnyBlank(data.getString("AWSAccessKeyId"),
                data.getString("Date"), data.getString("Signature"))) {
            throw new IllegalStateException("获取美团 S3 上传签名失败: 响应字段不完整");
        }
        return new S3Credential(data.getString("AWSAccessKeyId"), data.getString("Date"),
                data.getString("Signature"));
    }

    private String uploadCover(File coverFile, String creatorId) throws IOException {
        HttpUrl initUrl = HttpUrl.parse(CONTENTS_ORIGIN + "/api/author/upload/img/init")
                .newBuilder()
                .addQueryParameter("scene", "3")
                .addQueryParameter("creatorId", creatorId)
                .build();
        JSONObject init = executeApiJson("GET", initUrl.toString(), null, null,
                "初始化美团首帧封面");
        requireSuccess(init, "初始化美团首帧封面");
        JSONObject initData = init.getJSONObject("data");
        String coverKey = initData == null ? null : initData.getString("fileKey");
        if (StringUtils.isBlank(coverKey)) {
            throw new IllegalStateException("初始化美团首帧封面失败: fileKey 为空");
        }

        JSONObject token = executeApiJson("POST", "/api/author/upload/img/batoken",
                null, null, "获取美团封面上传凭证");
        requireSuccess(token, "获取美团封面上传凭证");
        JSONObject tokenData = token.getJSONObject("data");
        if (tokenData == null || StringUtils.isBlank(tokenData.getString("authorization"))) {
            throw new IllegalStateException("获取美团封面上传凭证失败: authorization 为空");
        }

        String uploadName = coverKey + ".png";
        HttpUrl uploadUrl = HttpUrl.parse(COVER_ORIGIN + "/extrastorage/tmpvideo")
                .newBuilder()
                .addQueryParameter("filename", uploadName)
                .addQueryParameter("isHttps", "true")
                .build();
        RequestBody fileBody = RequestBody.create(MediaType.parse("image/jpeg"), coverFile);
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", uploadName, fileBody)
                .build();
        Request.Builder uploadRequest = new Request.Builder().url(uploadUrl).post(multipart)
                .header("Authorization", tokenData.getString("authorization"))
                .header("time", tokenData.getString("expiretime"));
        addHeaders(uploadRequest, webSession.getBrowserIdentityHeaders());
        try (Response response = httpClient.newCall(uploadRequest.build()).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("上传美团首帧封面失败: HTTP " + response.code()
                        + ", body=" + truncate(responseBody, 1_000));
            }
            JSONObject uploadResult = JSON.parseObject(responseBody);
            if (uploadResult == null || !uploadResult.getBooleanValue("success")) {
                throw new IllegalStateException("上传美团首帧封面失败: "
                        + truncate(responseBody, 1_000));
            }
        }

        JSONObject completeBody = orderedObject();
        completeBody.put("key", coverKey);
        JSONObject complete = executeApiJson("POST", "/api/author/upload/img/complete",
                completeBody.toJSONString(), "application/json", "完成美团首帧封面上传");
        requireSuccess(complete, "完成美团首帧封面上传");
        return coverKey;
    }

    private void checkVideoContent(String key) throws IOException {
        HttpUrl url = HttpUrl.parse(CONTENTS_ORIGIN + "/api/author/videoUpload/checkContent")
                .newBuilder().addQueryParameter("fileName", key).build();
        JSONObject response = executeApiJson("GET", url.toString(), null, null,
                "检查美团视频内容");
        requireSuccess(response, "检查美团视频内容");
    }

    private String completeVideo(String key) throws IOException {
        HttpUrl url = HttpUrl.parse(CONTENTS_ORIGIN + "/api/author/videoUpload/complete")
                .newBuilder().addQueryParameter("fileName", key).build();
        JSONObject response = executeApiJson("GET", url.toString(), null, null,
                "完成美团视频上传");
        requireSuccess(response, "完成美团视频上传");
        String link = response.getString("data");
        if (StringUtils.isBlank(link)) {
            throw new IllegalStateException("完成美团视频上传失败: data 为空");
        }
        return link;
    }

    private void waitForContentPreCheck(String key) throws IOException {
        String objectUrl = s3ObjectUrl(key);
        JSONObject submitBody = orderedObject();
        submitBody.put("fileName", key);
        submitBody.put("url", objectUrl);
        submitBody.put("uploadPreCheckType", 1);
        JSONObject submit = executeApiJson("POST", "/api/author/upload/pre/check/task/submit",
                submitBody.toJSONString(), "application/json", "提交美团视频内容预检");
        requireSuccess(submit, "提交美团视频内容预检");
        JSONObject task = submit.getJSONObject("data");
        if (task == null || StringUtils.isBlank(task.getString("taskId"))) {
            throw new IllegalStateException("提交美团视频内容预检失败: taskId 为空");
        }

        JSONObject taskItem = orderedObject();
        taskItem.put("fileName", task.getString("fileName"));
        taskItem.put("url", task.getString("url"));
        taskItem.put("taskId", task.getString("taskId"));
        JSONArray fileList = new JSONArray();
        fileList.add(taskItem);
        JSONObject resultBody = orderedObject();
        resultBody.put("fileList", fileList);
        resultBody.put("uploadPreCheckType", 1);

        for (int attempt = 1; attempt <= 30; attempt++) {
            JSONObject result = executeApiJson("POST",
                    "/api/author/upload/pre/check/task/result", resultBody.toJSONString(),
                    "application/json", "查询美团视频内容预检");
            requireSuccess(result, "查询美团视频内容预检");
            JSONObject data = result.getJSONObject("data");
            JSONArray resultFiles = data == null ? null : data.getJSONArray("fileList");
            if (resultFiles != null && !resultFiles.isEmpty()
                    && resultFiles.getJSONObject(0).getBooleanValue("taskComplete")) {
                String message = resultFiles.getJSONObject(0).getString("msg");
                if (StringUtils.isNotBlank(message)) {
                    throw new IllegalStateException("美团视频内容预检未通过: " + message);
                }
                return;
            }
            sleep(2_000);
        }
        throw new IllegalStateException("等待美团视频内容预检超时");
    }

    private JSONObject executeApiJson(String method,
                                      String url,
                                      String body,
                                      String contentType,
                                      String operation) throws IOException {
        return webSession.executeOfficialApiJson(method, url, body, contentType, operation);
    }

    private RawHttpResponse executeSignedRaw(String method,
                                             String url,
                                             Map<String, String> headers,
                                             String body,
                                             String contentType,
                                             String operation) throws IOException {
        MeituanWebSession.SignedRequest signed = webSession.sign(method, url, body,
                contentType, headers);
        return replay(signed, body, contentType, operation);
    }

    private RawHttpResponse replay(MeituanWebSession.SignedRequest signed,
                                   String body,
                                   String contentType,
                                   String operation) throws IOException {
        RequestBody requestBody = null;
        if (!"GET".equalsIgnoreCase(signed.getMethod())
                && !"HEAD".equalsIgnoreCase(signed.getMethod())) {
            requestBody = RequestBody.create(StringUtils.isBlank(contentType)
                            ? null : MediaType.parse(contentType),
                    body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        }
        Request.Builder builder = new Request.Builder().url(signed.getUrl())
                .method(signed.getMethod(), requestBody);
        for (Map.Entry<String, String> header : signed.getHeaders().entrySet()) {
            String name = header.getKey();
            if (!name.startsWith(":") && !REPLAY_HEADER_BLOCKLIST.contains(name.toLowerCase())) {
                builder.header(name, header.getValue());
            }
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            webSession.acceptResponseCookies(response.request().url(), response.headers());
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException(operation + "失败: HTTP " + response.code()
                        + ", body=" + truncate(responseBody, 1_000));
            }
            return new RawHttpResponse(response.code(), responseBody);
        }
    }

    private static VideoInfo detectVideoInfo(File videoFile) {
        VideoSizeDetectCmd command = new VideoSizeDetectCmd(videoFile.getAbsolutePath());
        command.execute(30);
        if (!command.isNormalExit() || command.getWidth() <= 0 || command.getHeight() <= 0) {
            throw new IllegalStateException("读取美团视频尺寸失败: " + videoFile.getAbsolutePath());
        }
        return new VideoInfo(command.getWidth(), command.getHeight());
    }

    static JSONObject buildPublishBody(MeituanWorkMetaData metadata,
                                       MeituanWebSession.CreatorProfile profile,
                                       VideoInfo video,
                                       String videoKey,
                                       String videoLink,
                                       String coverKey) {
        JSONObject body = orderedObject();
        body.put("authorId", profile.getAuthorId());
        body.put("authorName", profile.getAuthorName());
        body.put("avatarUrl", profile.getAvatarUrl());
        body.put("creatorId", profile.getCreatorId());
        body.put("contentOrder", 1);
        body.put("videoLink", videoLink);
        body.put("videoHeight", video.getHeight());
        body.put("videoWidth", video.getWidth());
        body.put("coverImage", null);
        body.put("coverImageKey", coverKey);
        body.put("title", buildPublicTitle(metadata));
        body.put("description", StringUtils.trimToNull(metadata == null ? null : metadata.getDesc()));
        body.put("key", videoKey);
        body.put("topicIds", new JSONArray());
        body.put("isTemp", true);
        body.put("sunriseTime", null);
        body.put("attachInfoList", new JSONArray());
        body.put("activitySign", false);
        JSONObject declarations = orderedObject();
        declarations.put("6", "无需添加自主声明");
        body.put("authorDeclarations", declarations);
        body.put("pageVersion", "1.3.1");
        body.put("publishScene", 1);
        body.put("contentTagType", 10);
        return body;
    }

    static String buildPublicTitle(MeituanWorkMetaData metadata) {
        StringBuilder title = new StringBuilder(StringUtils.trimToEmpty(
                metadata == null ? null : metadata.getTitle()));
        Set<String> tags = new LinkedHashSet<>();
        if (metadata != null && metadata.getTags() != null) {
            for (String rawTag : metadata.getTags()) {
                String tag = StringUtils.trimToEmpty(rawTag);
                while (tag.startsWith("#")) {
                    tag = tag.substring(1).trim();
                }
                if (StringUtils.isNotBlank(tag)) {
                    tags.add(tag);
                }
            }
        }
        for (String tag : tags) {
            String hashtag = "#" + tag;
            if (title.indexOf(hashtag) < 0) {
                if (title.length() > 0) {
                    title.append(' ');
                }
                title.append(hashtag);
            }
        }
        if (title.length() > 200) {
            return title.substring(0, 200);
        }
        return title.toString();
    }

    static String buildCompleteMultipartXml(List<UploadedPart> parts) {
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        for (UploadedPart part : parts) {
            xml.append("<Part><PartNumber>").append(part.getPartNumber())
                    .append("</PartNumber><ETag>").append(part.getEtag())
                    .append("</ETag></Part>");
        }
        return xml.append("</CompleteMultipartUpload>").toString();
    }

    private static Map<String, String> storageHeaders(S3Credential credential) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "AWS " + credential.accessKeyId + ":"
                + credential.signature);
        headers.put("x-amz-date", credential.date);
        headers.put("Content-Type", VIDEO_CONTENT_TYPE);
        return headers;
    }

    private static void addHeaders(Request.Builder request, Map<String, String> headers) {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
    }

    private static String s3ObjectUrl(String key) {
        return S3_ORIGIN + "/" + S3_BUCKET + "/" + key;
    }

    private static String extractXmlTag(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + ">([^<]+)</"
                + Pattern.quote(tag) + ">", Pattern.DOTALL).matcher(StringUtils.defaultString(xml));
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static void requireSuccess(JSONObject response, String operation) {
        if (response == null || response.getIntValue("code") != 0
                || !response.getBooleanValue("success")) {
            throw new IllegalStateException(operation + "失败: code="
                    + (response == null ? "null" : response.getInteger("code"))
                    + ", message=" + (response == null ? "null" : response.getString("message")));
        }
    }

    private static JSONObject orderedObject() {
        return new JSONObject(true);
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待美团视频处理时被中断", e);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return StringUtils.defaultString(value);
        }
        return value.substring(0, maxLength);
    }

    private static final class FileSliceRequestBody extends RequestBody {
        private final File file;
        private final long offset;
        private final long length;
        private final MediaType mediaType;

        private FileSliceRequestBody(File file,
                                     long offset,
                                     long length,
                                     MediaType mediaType) {
            this.file = file;
            this.offset = offset;
            this.length = length;
            this.mediaType = mediaType;
        }

        @Override
        public MediaType contentType() {
            return mediaType;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                input.seek(offset);
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) {
                        throw new IOException("读取美团视频分片时提前到达文件结尾");
                    }
                    sink.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        }
    }

    @Value
    private static class S3Credential {
        String accessKeyId;
        String date;
        String signature;
    }

    @Value
    static class UploadedPart {
        int partNumber;
        String etag;
    }

    @Value
    static class VideoInfo {
        int width;
        int height;
    }

    @Value
    private static class RawHttpResponse {
        int status;
        String body;
    }

    @Value
    public static class UploadResult {
        String contentId;
        String videoKey;
        String coverKey;
    }
}
