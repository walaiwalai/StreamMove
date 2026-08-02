package com.sh.engine.processor.uploader.douyin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** Java media-transfer implementation of the captured creator.douyin.com web upload chain. */
@Slf4j
public final class DouyinWebUploadClient implements AutoCloseable {
    private static final String CREATOR_ORIGIN = "https://creator.douyin.com";
    private static final String VOD_ENDPOINT = "https://vod.bytedanceapi.com/";
    private static final String IMAGEX_ENDPOINT = "https://imagex.bytedanceapi.com/";
    private static final String REGION = "cn-north-1";
    private static final String IMAGE_SERVICE_ID = "jm8ajry58r";
    private static final int MIN_PART_SIZE = 5 * 1024 * 1024;
    private static final int MAX_RETRY = 3;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> REPLAY_HEADER_BLOCKLIST = Arrays.asList(
            "host", "content-length", "connection", "accept-encoding", "transfer-encoding"
    );

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build();
    private final DouyinAwsV4Signer awsSigner = new DouyinAwsV4Signer();
    private final DouyinWebRequestSigner webSigner;
    private Map<String, String> browserIdentityHeaders = Collections.emptyMap();

    public DouyinWebUploadClient(File storageStateFile) {
        this.webSigner = new DouyinWebRequestSigner(storageStateFile);
    }

    public UploadResult upload(File videoFile, File coverFile, DouyinWorkMetaData metadata) throws Exception {
        if (!videoFile.isFile()) {
            throw new IllegalArgumentException("视频文件不存在: " + videoFile.getAbsolutePath());
        }
        if (!coverFile.isFile()) {
            throw new IllegalArgumentException("封面文件不存在: " + coverFile.getAbsolutePath());
        }

        DouyinAwsV4Signer.Credentials credentials = fetchTemporaryCredentials();
        VideoUploadAddress videoAddress = allocateVideo(credentials, videoFile.length());
        uploadVideo(videoFile, videoAddress);
        commitVideo(credentials, videoAddress);
        enableVideo(videoAddress.getVideoId());
        waitForTranscode(videoAddress.getVideoId());

        CoverUploadResult cover = uploadCover(coverFile, fetchTemporaryCredentials(),
                videoAddress.getUserId());
        JSONObject publishBody = buildPublishBody(videoFile, metadata, videoAddress.getVideoId(), cover);
        String publishUrl = "/web/api/media/aweme/create_v2/?read_aid=2906";
        String publishJson = publishBody.toJSONString();
        // create_v2 mutates account state. Send it exactly once in the browser security context;
        // using a browser signing probe followed by Java replay can be observed as a duplicate.
        JSONObject publishResult = executeCreatorBrowserJson("POST", publishUrl, publishJson,
                "application/json;charset=UTF-8", "发布抖音作品");
        int publishStatus = publishResult.getIntValue("status_code");
        if (publishStatus != 0 && publishStatus != 517) {
            requireStatusCodeZero(publishResult, "发布抖音作品");
        }
        if (publishStatus == 517) {
            log.info("douyin video is already published; treating create_v2 as idempotent success");
        }
        String itemId = publishResult.getString("item_id");
        return new UploadResult(StringUtils.defaultIfBlank(itemId, videoAddress.getVideoId()),
                videoAddress.getVideoId(), cover.getUri());
    }

    @Override
    public void close() {
        webSigner.close();
    }

    private DouyinAwsV4Signer.Credentials fetchTemporaryCredentials() throws Exception {
        JSONObject response = executeAuthenticatedCreatorJson("GET",
                "/web/api/media/upload/auth/v5/", null, null, "获取抖音上传凭证");
        requireStatusCodeZero(response, "获取抖音上传凭证");
        JSONObject auth = JSON.parseObject(response.getString("auth"));
        if (auth == null || StringUtils.isAnyBlank(auth.getString("AccessKeyID"),
                auth.getString("SecretAccessKey"), auth.getString("SessionToken"))) {
            throw new IllegalStateException("抖音上传凭证响应缺少 AccessKeyID/SecretAccessKey/SessionToken");
        }
        return new DouyinAwsV4Signer.Credentials(auth.getString("AccessKeyID"),
                auth.getString("SecretAccessKey"), auth.getString("SessionToken"));
    }

    private VideoUploadAddress allocateVideo(DouyinAwsV4Signer.Credentials credentials,
                                               long fileSize) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("Action", "ApplyUploadInner");
        query.put("Version", "2020-11-19");
        query.put("SpaceName", "aweme");
        query.put("FileType", "video");
        query.put("IsInner", "1");
        query.put("FileSize", String.valueOf(fileSize));
        query.put("app_id", "2906");
        query.put("user_id", webSigner.getUserId());
        query.put("s", randomString(11));
        DouyinAwsV4Signer.SignedAwsRequest signed = awsSigner.sign("GET", VOD_ENDPOINT,
                query, null, credentials, REGION, "vod");
        JSONObject response = executeBrowserControlJson(signed, "GET", null, null,
                "分配抖音视频上传地址");

        JSONObject result = requireObject(response, "Result", "分配抖音视频上传地址");
        JSONObject inner = requireObject(result, "InnerUploadAddress", "分配抖音视频上传地址");
        JSONArray nodes = inner.getJSONArray("UploadNodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException("分配抖音视频上传地址失败: UploadNodes 为空");
        }
        JSONObject node = nodes.getJSONObject(0);
        JSONArray stores = node.getJSONArray("StoreInfos");
        if (stores == null || stores.isEmpty()) {
            throw new IllegalStateException("分配抖音视频上传地址失败: StoreInfos 为空");
        }
        JSONObject store = stores.getJSONObject(0);
        JSONObject storageHeader = store.getJSONObject("StorageHeader");
        String userId = storageHeader == null ? webSigner.getUserId() : storageHeader.getString("USER_ID");
        return new VideoUploadAddress(node.getString("Vid"), node.getString("UploadHost"),
                store.getString("StoreUri"), store.getString("Auth"), userId,
                node.getString("SessionKey"));
    }

    private void uploadVideo(File videoFile, VideoUploadAddress address) throws Exception {
        String baseUrl = "https://" + address.getUploadHost() + "/upload/v1/" + address.getStoreUri();
        String boundary = "----WebKitFormBoundary" + randomString(16);
        byte[] initBody = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = storageHeaders(address.getAuthorization(), address.getUserId());
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        JSONObject initResponse = executeJson("POST", baseUrl + "?uploadmode=part&phase=init",
                headers, initBody, headers.get("Content-Type"));
        requireStorageSuccess(initResponse, "初始化抖音视频分片上传");
        String uploadId = requireObject(initResponse, "data", "初始化抖音视频分片上传")
                .getString("uploadid");
        if (StringUtils.isBlank(uploadId)) {
            throw new IllegalStateException("初始化抖音视频分片上传失败: uploadid 为空");
        }

        int partCount = calculatePartCount(videoFile.length());
        StringBuilder finishBody = new StringBuilder();
        for (int index = 0; index < partCount; index++) {
            long offset = (long) index * MIN_PART_SIZE;
            int length = (int) Math.min(Integer.MAX_VALUE,
                    index == partCount - 1 ? videoFile.length() - offset : MIN_PART_SIZE);
            byte[] part = VideoFileUtil.fetchBlock(videoFile, offset, length);
            String crc32 = crc32(part);
            uploadVideoPart(baseUrl, address, uploadId, index + 1, offset, part, crc32);
            if (finishBody.length() > 0) {
                finishBody.append(',');
            }
            finishBody.append(index + 1).append(':').append(crc32);
            log.info("douyin video upload progress: {}/{}, file: {}",
                    index + 1, partCount, videoFile.getAbsolutePath());
        }

        headers = storageHeaders(address.getAuthorization(), address.getUserId());
        headers.put("Content-Type", "text/plain;charset=UTF-8");
        String finishUrl = baseUrl + "?uploadmode=part&phase=finish&uploadid=" + uploadId;
        JSONObject finishResponse = executeJson("POST", finishUrl, headers,
                finishBody.toString().getBytes(StandardCharsets.UTF_8), headers.get("Content-Type"));
        requireStorageSuccess(finishResponse, "完成抖音视频分片上传");
    }

    private void commitVideo(DouyinAwsV4Signer.Credentials credentials,
                             VideoUploadAddress address) throws Exception {
        JSONObject body = orderedObject();
        body.put("SessionKey", address.getSessionKey());
        JSONArray functions = new JSONArray();
        JSONObject getMeta = orderedObject();
        getMeta.put("name", "GetMeta");
        functions.add(getMeta);
        JSONObject snapshot = orderedObject();
        snapshot.put("name", "Snapshot");
        JSONObject snapshotInput = orderedObject();
        snapshotInput.put("SnapshotTime", 0);
        snapshot.put("input", snapshotInput);
        functions.add(snapshot);
        body.put("Functions", functions);
        byte[] requestBody = body.toJSONString().getBytes(StandardCharsets.UTF_8);

        Map<String, String> query = new LinkedHashMap<>();
        query.put("Action", "CommitUploadInner");
        query.put("Version", "2020-11-19");
        query.put("SpaceName", "aweme");
        query.put("app_id", "2906");
        query.put("user_id", address.getUserId());
        DouyinAwsV4Signer.SignedAwsRequest signed = awsSigner.sign("POST", VOD_ENDPOINT,
                query, requestBody, credentials, REGION, "vod");
        JSONObject response = executeBrowserControlJson(signed, "POST",
                new String(requestBody, StandardCharsets.UTF_8), "text/plain;charset=UTF-8",
                "提交抖音视频上传");
        requireCommittedVideo(response, address);
    }

    private void requireCommittedVideo(JSONObject response, VideoUploadAddress address) {
        JSONArray results = requireObject(response, "Result", "提交抖音视频上传")
                .getJSONArray("Results");
        JSONObject committed = results == null || results.isEmpty() ? null : results.getJSONObject(0);
        if (committed == null || !address.getVideoId().equals(committed.getString("Vid"))
                || committed.getJSONObject("VideoMeta") == null) {
            throw new IllegalStateException("提交抖音视频上传失败: Results/Vid/VideoMeta 无效");
        }
    }

    private void uploadVideoPart(String baseUrl,
                                 VideoUploadAddress address,
                                 String uploadId,
                                 int partNumber,
                                 long offset,
                                 byte[] part,
                                 String crc32) throws Exception {
        String url = baseUrl + "?uploadid=" + uploadId + "&part_number=" + partNumber
                + "&phase=transfer&part_offset=" + offset;
        Map<String, String> headers = storageHeaders(address.getAuthorization(), address.getUserId());
        headers.put("Content-CRC32", crc32);
        headers.put("Content-Disposition", "attachment; filename=\"undefined\"");
        headers.put("Content-Type", "application/octet-stream");

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                JSONObject response = executeJson("POST", url, headers, part, "application/octet-stream");
                requireStorageSuccess(response, "上传抖音视频分片 " + partNumber);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("douyin video part upload failed, part: {}, attempt: {}/{}",
                        partNumber, attempt, MAX_RETRY, e);
            }
        }
        throw lastError;
    }

    private void waitForTranscode(String videoId) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (System.currentTimeMillis() < deadline) {
            JSONObject response = executeAuthenticatedCreatorJson("GET",
                    "/web/api/media/video/transend/?video_id=" + videoId, null, null,
                    "查询抖音视频转码状态");
            requireStatusCodeZero(response, "查询抖音视频转码状态");
            if (response.getIntValue("encode") == 1) {
                return;
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("等待抖音视频转码超时: " + videoId);
    }

    private void enableVideo(String videoId) throws Exception {
        JSONObject response = executeAuthenticatedCreatorJson("GET",
                "/web/api/media/video/enable/?video_id=" + videoId, null, null,
                "注册抖音视频转码");
        requireStatusCodeZero(response, "注册抖音视频转码");
    }

    private CoverUploadResult uploadCover(File coverFile,
                                          DouyinAwsV4Signer.Credentials credentials,
                                          String userId) throws Exception {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("Action", "ApplyImageUpload");
        query.put("Version", "2018-08-01");
        query.put("ServiceId", IMAGE_SERVICE_ID);
        query.put("app_id", "2906");
        query.put("user_id", userId);
        query.put("s", randomString(11));
        DouyinAwsV4Signer.SignedAwsRequest applyRequest = awsSigner.sign("GET", IMAGEX_ENDPOINT,
                query, null, credentials, REGION, "imagex");
        JSONObject applyResponse = executeBrowserControlJson(applyRequest, "GET", null, null,
                "分配抖音封面上传地址");
        JSONObject uploadAddress = requireObject(requireObject(applyResponse, "Result", "分配抖音封面上传地址"),
                "UploadAddress", "分配抖音封面上传地址");
        JSONArray stores = uploadAddress.getJSONArray("StoreInfos");
        JSONArray hosts = uploadAddress.getJSONArray("UploadHosts");
        if (stores == null || stores.isEmpty() || hosts == null || hosts.isEmpty()) {
            throw new IllegalStateException("分配抖音封面上传地址失败: StoreInfos/UploadHosts 为空");
        }
        JSONObject store = stores.getJSONObject(0);
        byte[] image = Files.readAllBytes(coverFile.toPath());
        String uploadUrl = "https://" + hosts.getString(0) + "/upload/v1/" + store.getString("StoreUri");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", store.getString("Auth"));
        headers.put("Content-CRC32", crc32(image));
        headers.put("Content-Disposition", "attachment; filename=\"undefined\"");
        headers.put("Content-Type", "application/octet-stream");
        headers.put("x-storage-u", userId);
        headers = withBrowserIdentity(headers);
        JSONObject uploadResponse = executeJson("POST", uploadUrl, headers, image, "application/octet-stream");
        requireStorageSuccess(uploadResponse, "上传抖音封面");

        JSONObject commitBody = new JSONObject(true);
        commitBody.put("SessionKey", uploadAddress.getString("SessionKey"));
        byte[] commitBytes = commitBody.toJSONString().getBytes(StandardCharsets.UTF_8);
        query = new LinkedHashMap<>();
        query.put("Action", "CommitImageUpload");
        query.put("Version", "2018-08-01");
        query.put("ServiceId", IMAGE_SERVICE_ID);
        query.put("app_id", "2906");
        query.put("user_id", userId);
        query.put("s", randomString(11));
        DouyinAwsV4Signer.SignedAwsRequest commitRequest = awsSigner.sign("POST", IMAGEX_ENDPOINT,
                query, commitBytes, credentials, REGION, "imagex");
        JSONObject commitResponse = executeBrowserControlJson(commitRequest, "POST",
                new String(commitBytes, StandardCharsets.UTF_8), "application/json;charset=UTF-8",
                "提交抖音封面");
        JSONArray results = requireObject(commitResponse, "Result", "提交抖音封面")
                .getJSONArray("Results");
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("提交抖音封面失败: Results 为空");
        }
        String uri = results.getJSONObject(0).getString("Uri");

        okhttp3.HttpUrl getUrl = okhttp3.HttpUrl.parse(CREATOR_ORIGIN + "/aweme/v1/creator/get/url/")
                .newBuilder().addQueryParameter("uri", uri).build();
        JSONObject urlResponse = executeAuthenticatedCreatorJson("GET", getUrl.toString(),
                null, null, "获取抖音封面地址");
        requireStatusCodeZero(urlResponse, "获取抖音封面地址");
        JSONArray urlList = requireObject(urlResponse, "url", "获取抖音封面地址")
                .getJSONArray("url_list");
        if (urlList == null || urlList.isEmpty()) {
            throw new IllegalStateException("获取抖音封面地址失败: url_list 为空");
        }
        return new CoverUploadResult(uri, urlList.getString(0));
    }

    private JSONObject buildPublishBody(File videoFile,
                                        DouyinWorkMetaData metadata,
                                        String videoId,
                                        CoverUploadResult cover) {
        JSONObject common = buildPublishCommon(metadata, videoId);
        JSONObject coverInfo = orderedObject();
        coverInfo.put("videoName", videoFile.getName());
        coverInfo.put("uri", cover.getUri());
        coverInfo.put("url", cover.getUrl());
        coverInfo.put("posterDelay", 0);
        coverInfo.put("firstFrameCoverUri", cover.getUri());

        JSONObject recommendCoverInfo = orderedObject();
        recommendCoverInfo.put("isFromRecommend", false);
        recommendCoverInfo.put("isDefaultSelect", true);
        recommendCoverInfo.put("isRecommendClickFrom", "");
        recommendCoverInfo.put("selectInfo", orderedObject());
        recommendCoverInfo.put("editingInfo", orderedObject());

        JSONObject recommendServerInfo = orderedObject();
        recommendServerInfo.put("res", new JSONArray());
        recommendServerInfo.put("times", new JSONArray());

        JSONObject extend = orderedObject();
        extend.put("recommendServerInfo", recommendServerInfo);
        extend.put("recommendCoverList", new JSONArray());
        extend.put("recommendCoverInfo", recommendCoverInfo);
        extend.put("recommendCoverTime", 0);
        extend.put("coverInfo", coverInfo);
        extend.put("coverUrl", cover.getUrl());
        extend.put("coverHorizontalInfo", null);
        extend.put("coverHorizontalUrl", "");
        extend.put("pasterInfo", null);
        extend.put("stateInfo", null);
        extend.put("croppedCoverInfo", null);
        extend.put("uploadBackgroundInfo", null);
        extend.put("uploadPasterInfo", null);
        extend.put("uploadCoverStateInfo", null);
        JSONObject xiguaCoverInfo = orderedObject();
        xiguaCoverInfo.put("posterDelay", 0);
        extend.put("xiguaCoverInfo", xiguaCoverInfo);
        extend.put("xiguaPasterInfo", null);
        extend.put("xiguaStateInfo", null);
        extend.put("xiguaUploadCoverStateInfo", null);
        extend.put("xiguaUploadBackgroundInfo", null);
        extend.put("xiguaUploadPasterInfo", null);
        extend.put("editXigua", false);
        extend.put("coverSource", "");
        JSONArray previewVideoList = new JSONArray();
        JSONObject currentPreview = orderedObject();
        currentPreview.put("isCurrent", true);
        previewVideoList.add(currentPreview);
        extend.put("previewVideoList", previewVideoList);

        JSONObject coverObject = orderedObject();
        coverObject.put("cover_text_uri", null);
        coverObject.put("cover_text", null);
        coverObject.put("poster", cover.getUri());
        coverObject.put("poster_delay", 0);
        coverObject.put("cover_tools_extend_info", extend.toJSONString());
        coverObject.put("cover_tools_info", "{}");

        JSONObject chapterTools = orderedObject();
        chapterTools.put("chapter_recommend_detail", new JSONArray());
        chapterTools.put("chapter_recommend_abstract", "");
        chapterTools.put("chapter_source", 2);
        chapterTools.put("chapter_recommend_type", -2);
        chapterTools.put("create_date", System.currentTimeMillis() / 1000);
        chapterTools.put("is_pc", "1");
        chapterTools.put("is_pre_generated", "0");
        chapterTools.put("is_syn", "1");
        JSONObject chapterValue = orderedObject();
        chapterValue.put("chapter_abstract", "");
        chapterValue.put("chapter_details", new JSONArray());
        chapterValue.put("chapter_type", 1);
        chapterValue.put("chapter_tools_info", chapterTools);
        JSONObject chapter = orderedObject();
        chapter.put("chapter", chapterValue.toJSONString());

        JSONObject selectedMember = orderedObject();
        selectedMember.put("is_selected_member_video", false);
        JSONObject sync = orderedObject();
        sync.put("should_sync", false);
        sync.put("sync_to_toutiao", 0);
        JSONObject assistant = orderedObject();
        assistant.put("is_preview", 0);
        assistant.put("is_post_assistant", 1);

        JSONObject item = orderedObject();
        item.put("common", common);
        item.put("cover", coverObject);
        item.put("mix", orderedObject());
        item.put("selected_member", selectedMember);
        item.put("chapter", chapter);
        item.put("anchor", orderedObject());
        item.put("sync", sync);
        item.put("open_platform", orderedObject());
        item.put("assistant", assistant);
        JSONObject body = orderedObject();
        body.put("item", item);
        return body;
    }

    static JSONObject buildPublishCommon(DouyinWorkMetaData metadata, String videoId) {
        String rawTitle = metadata == null ? "" : StringUtils.defaultString(metadata.getTitle());
        rawTitle = rawTitle.replace('\r', ' ').replace('\n', ' ').trim();
        String title = safeLeft(rawTitle, 30);
        String description = metadata == null ? "" : StringUtils.trimToEmpty(metadata.getDesc());
        description = safeLeft(description, 1_000);

        StringBuilder caption = new StringBuilder(description);
        JSONArray textExtra = new JSONArray();
        List<String> tags = metadata == null ? null : metadata.getTags();
        if (tags != null) {
            for (String rawTag : tags) {
                String tag = normalizeTag(rawTag);
                if (StringUtils.isBlank(tag)) {
                    continue;
                }
                String token = "#" + tag;
                int separatorLength = caption.length() == 0 ? 0 : 1;
                if (caption.length() + separatorLength + token.length() > 1_000) {
                    continue;
                }
                if (separatorLength > 0) {
                    caption.append(' ');
                }
                int captionStart = caption.length();
                caption.append(token);
                int textPrefixLength = title.length() == 0 ? 0 : title.length() + 1;

                JSONObject hashtag = orderedObject();
                hashtag.put("start", textPrefixLength + captionStart);
                hashtag.put("end", textPrefixLength + caption.length());
                hashtag.put("type", 1);
                hashtag.put("hashtag_name", tag);
                hashtag.put("hashtag_id", 0);
                hashtag.put("user_id", "");
                hashtag.put("caption_start", captionStart);
                hashtag.put("caption_end", caption.length());
                textExtra.add(hashtag);
            }
        }

        String captionValue = caption.toString();
        String text = title;
        if (StringUtils.isNotBlank(captionValue)) {
            text = StringUtils.isBlank(title) ? captionValue : title + " " + captionValue;
        }

        JSONObject common = orderedObject();
        common.put("text", text);
        common.put("caption", captionValue);
        common.put("item_title", title);
        common.put("activity", "[]");
        common.put("text_extra", textExtra.toJSONString());
        common.put("challenges", "[]");
        common.put("mentions", "[]");
        common.put("hashtag_source", textExtra.isEmpty() ? "" : "search/search");
        common.put("hot_sentence", "");
        common.put("interaction_stickers", "[]");
        common.put("visibility_type", 0);
        common.put("download", 1);
        common.put("timing", 0);
        common.put("creation_id", randomString(8) + System.currentTimeMillis());
        common.put("media_type", 4);
        common.put("video_id", videoId);
        common.put("music_source", 0);
        common.put("music_id", null);
        return common;
    }

    private static String normalizeTag(String rawTag) {
        String tag = StringUtils.trimToEmpty(rawTag);
        while (tag.startsWith("#")) {
            tag = tag.substring(1).trim();
        }
        return tag;
    }

    private static String safeLeft(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        String result = value.substring(0, maxLength);
        if (!result.isEmpty() && Character.isHighSurrogate(result.charAt(result.length() - 1))) {
            return result.substring(0, result.length() - 1);
        }
        return result;
    }

    private JSONObject executeWebJson(String method,
                                      String url,
                                      String body,
                                      String contentType) throws Exception {
        DouyinWebRequestSigner.SignedRequest signed = webSigner.sign(method, url, body, contentType);
        browserIdentityHeaders = new LinkedHashMap<>(signed.getHeaders());
        Request.Builder request = new Request.Builder().url(signed.getUrl());
        for (Map.Entry<String, String> header : signed.getHeaders().entrySet()) {
            if (!REPLAY_HEADER_BLOCKLIST.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                request.header(header.getKey(), header.getValue());
            }
        }
        if ("GET".equalsIgnoreCase(method)) {
            request.get();
        } else {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            request.method(method, RequestBody.create(MediaType.parse(contentType), bytes));
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            webSigner.acceptResponseCookies(response.request().url(), response.headers());
            return readJsonResponse(response, "抖音创作者中心接口");
        }
    }

    private JSONObject executeBrowserControlJson(DouyinAwsV4Signer.SignedAwsRequest signed,
                                                   String method,
                                                   String body,
                                                   String contentType,
                                                   String operation) {
        Map<String, String> headers = new LinkedHashMap<>(signed.getHeaders());
        if (StringUtils.isNotBlank(contentType)) {
            headers.put("Content-Type", contentType);
        }
        DouyinWebRequestSigner.BrowserHttpResponse response =
                webSigner.executeSignedControlRequest(method, signed.getUrl(), headers, body);
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(operation + "失败: HTTP " + response.getStatus());
        }
        JSONObject json = JSON.parseObject(response.getBody());
        if (json == null) {
            throw new IllegalStateException(operation + "返回了空JSON");
        }
        return json;
    }

    private JSONObject executeCreatorBrowserJson(String method,
                                                  String url,
                                                  String body,
                                                  String contentType,
                                                  String operation) {
        DouyinWebRequestSigner.BrowserHttpResponse response =
                webSigner.executeCreatorRequest(method, url, body, contentType);
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException(operation + "失败: HTTP " + response.getStatus());
        }
        JSONObject json = JSON.parseObject(response.getBody());
        if (json == null) {
            throw new IllegalStateException(operation + "返回了空JSON");
        }
        return json;
    }

    private JSONObject executeAuthenticatedCreatorJson(String method,
                                                        String url,
                                                        String body,
                                                        String contentType,
                                                        String operation) throws Exception {
        JSONObject result = executeWebJson(method, url, body, contentType);
        if (result.getIntValue("status_code") == 8) {
            log.info("douyin Java HTTP session replay expired, retrying in browser session: {}",
                    operation);
            return executeCreatorBrowserJson(method, url, body, contentType, operation);
        }
        return result;
    }

    private JSONObject executeJson(String method,
                                   String url,
                                   Map<String, String> headers,
                                   byte[] body,
                                   String contentType) throws Exception {
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if ("GET".equalsIgnoreCase(method)) {
            request.get();
        } else {
            MediaType mediaType = MediaType.parse(StringUtils.defaultIfBlank(contentType,
                    "application/octet-stream"));
            request.method(method, RequestBody.create(mediaType, body == null ? new byte[0] : body));
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            return readJsonResponse(response, url);
        }
    }

    private JSONObject readJsonResponse(Response response, String operation) throws Exception {
        String responseBody = response.body() == null ? "" : response.body().string();
        if (!response.isSuccessful()) {
            throw new IllegalStateException(operation + " HTTP失败: " + response.code() + " "
                    + truncate(responseBody, 1_000));
        }
        JSONObject json = JSON.parseObject(responseBody);
        if (json == null) {
            throw new IllegalStateException(operation + " 返回了空JSON");
        }
        return json;
    }

    private Map<String, String> storageHeaders(String authorization, String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", authorization);
        headers.put("Origin", CREATOR_ORIGIN);
        headers.put("x-storage-mode", "gateway");
        headers.put("x-storage-u", userId);
        return withBrowserIdentity(headers);
    }

    private Map<String, String> withBrowserIdentity(Map<String, String> source) {
        Map<String, String> headers = new LinkedHashMap<>(source);
        copyHeader(browserIdentityHeaders, headers, "user-agent", "User-Agent");
        copyHeader(browserIdentityHeaders, headers, "sec-ch-ua", "sec-ch-ua");
        copyHeader(browserIdentityHeaders, headers, "sec-ch-ua-mobile", "sec-ch-ua-mobile");
        copyHeader(browserIdentityHeaders, headers, "sec-ch-ua-platform", "sec-ch-ua-platform");
        headers.put("Referer", CREATOR_ORIGIN + "/");
        return headers;
    }

    private static void copyHeader(Map<String, String> source,
                                   Map<String, String> target,
                                   String sourceName,
                                   String targetName) {
        for (Map.Entry<String, String> header : source.entrySet()) {
            if (sourceName.equalsIgnoreCase(header.getKey())) {
                target.put(targetName, header.getValue());
                return;
            }
        }
    }

    private static int calculatePartCount(long fileSize) {
        if (fileSize <= MIN_PART_SIZE) {
            return 1;
        }
        return (int) (fileSize / MIN_PART_SIZE);
    }

    private static void requireStatusCodeZero(JSONObject response, String operation) {
        if (response.getIntValue("status_code") != 0) {
            throw new IllegalStateException(operation + "失败: status_code="
                    + response.getIntValue("status_code") + ", status_msg="
                    + response.getString("status_msg"));
        }
    }

    private static void requireStorageSuccess(JSONObject response, String operation) {
        if (response.getIntValue("code") != 2000) {
            throw new IllegalStateException(operation + "失败: code=" + response.getIntValue("code")
                    + ", message=" + response.getString("message"));
        }
    }

    private static JSONObject requireObject(JSONObject parent, String key, String operation) {
        JSONObject value = parent == null ? null : parent.getJSONObject(key);
        if (value == null) {
            throw new IllegalStateException(operation + "失败: 缺少字段 " + key);
        }
        return value;
    }

    private static JSONObject orderedObject() {
        return new JSONObject(true);
    }

    private static String crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format("%08x", crc.getValue());
    }

    private static String randomString(int length) {
        final char[] alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return value.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return StringUtils.defaultString(value);
        }
        return value.substring(0, maxLength);
    }

    @Value
    private static class VideoUploadAddress {
        String videoId;
        String uploadHost;
        String storeUri;
        String authorization;
        String userId;
        String sessionKey;
    }

    @Value
    private static class CoverUploadResult {
        String uri;
        String url;
    }

    @Value
    public static class UploadResult {
        String itemId;
        String videoId;
        String coverUri;
    }
}
