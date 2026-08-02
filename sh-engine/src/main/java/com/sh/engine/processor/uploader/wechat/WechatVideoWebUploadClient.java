package com.sh.engine.processor.uploader.wechat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.processor.uploader.meta.WechatVideoMetaData;
import lombok.Value;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Coordinates the captured WeChat Channels upload, clip and public post creation chain. */
public final class WechatVideoWebUploadClient implements AutoCloseable {
    private static final String API_ROOT =
            "/micro/content/cgi-bin/mmfinderassistant-bin";
    private static final String TRACE_PATH = API_ROOT + "/post/get-finder-post-trace-key";
    private static final String LOCATION_PATH = API_ROOT + "/helper/helper_search_location";
    private static final String CLIP_PATH = API_ROOT + "/post/post_clip_video";
    private static final String PRECHECK_PATH = API_ROOT + "/post/check_finder_comm_face";
    private static final String CREATE_PATH = API_ROOT + "/post/post_create";

    private final WechatVideoWebSession session;
    private final WechatVideoWebSession.UploadContext uploadContext;
    private final WechatVideoStorageClient storageClient;

    public WechatVideoWebUploadClient(File storageStateFile) {
        this.session = new WechatVideoWebSession(storageStateFile);
        this.uploadContext = session.getUploadContext();
        this.storageClient = new WechatVideoStorageClient(uploadContext);
    }

    public UploadResult upload(File videoFile,
                               File firstFrameCover,
                               WechatVideoMetaData metadata) throws Exception {
        validateFiles(videoFile, firstFrameCover);
        VideoInfo videoInfo = detectVideoInfo(videoFile);
        PostText postText = buildPostText(metadata);
        JSONObject location = fetchDefaultLocation();
        String traceKey = fetchTraceKey();

        WechatVideoStorageClient.StorageUploadResult video = storageClient.upload(videoFile,
                uploadContext.getVideoFileType(), videoFile.getName());
        WechatVideoStorageClient.StorageUploadResult cover = storageClient.upload(firstFrameCover,
                uploadContext.getPictureFileType(), "finder_video_img.jpeg");
        TraceInfo trace = new TraceInfo(traceKey, video.getUploadStartEpochSecond(),
                video.getUploadEndEpochSecond());
        ClipTicket clip = createClip(video, videoInfo, trace);
        JSONObject publishBody = buildPublishBody(videoFile, videoInfo, video, cover, clip,
                trace, postText, location, uploadContext);

        runContentPrecheck(postText);
        JSONObject response = session.post(CREATE_PATH, publishBody, "发表微信视频号作品");
        requireOk(response, "发表微信视频号作品");
        String clientId = publishBody.getString("clientid");
        return new UploadResult(clientId, clip.draftId, video.getDownloadUrl(),
                cover.getDownloadUrl());
    }

    @Override
    public void close() {
        session.close();
    }

    private String fetchTraceKey() {
        JSONObject response = session.post(TRACE_PATH, new JSONObject(true),
                "获取微信视频号上传追踪标识");
        requireOk(response, "获取微信视频号上传追踪标识");
        JSONObject data = response.getJSONObject("data");
        String traceKey = data == null ? null : data.getString("traceKey");
        if (StringUtils.isBlank(traceKey)) {
            throw new IllegalStateException("获取微信视频号上传追踪标识失败: 缺少 traceKey");
        }
        return traceKey;
    }

    private JSONObject fetchDefaultLocation() {
        JSONObject body = new JSONObject(true);
        body.put("query", "");
        body.put("cookies", "");
        body.put("longitude", 0);
        body.put("latitude", 0);
        JSONObject response = session.post(LOCATION_PATH, body, "获取微信视频号默认位置");
        requireOk(response, "获取微信视频号默认位置");
        JSONObject data = response.getJSONObject("data");
        JSONObject address = data == null ? null : data.getJSONObject("address");
        return buildLocation(address);
    }

    private ClipTicket createClip(WechatVideoStorageClient.StorageUploadResult video,
                                  VideoInfo videoInfo,
                                  TraceInfo trace) {
        int[] target = targetSize(videoInfo.width, videoInfo.height);
        JSONObject body = new JSONObject(true);
        body.put("url", video.getDownloadUrl());
        body.put("timeStart", 0);
        body.put("cropDuration", 0);
        body.put("height", videoInfo.height);
        body.put("width", videoInfo.width);
        body.put("x", 0);
        body.put("y", 0);
        JSONObject origin = new JSONObject(true);
        origin.put("width", videoInfo.width);
        origin.put("height", videoInfo.height);
        origin.put("duration", videoInfo.duration);
        origin.put("fileSize", videoInfo.fileSize);
        body.put("clipOriginVideoInfo", origin);
        body.put("traceInfo", buildTraceInfo(trace));
        body.put("targetWidth", target[0]);
        body.put("targetHeight", target[1]);
        body.put("type", 4);
        body.put("useAstraThumbCover",
                uploadContext.getEnableAllowAstraThumbCover() == 1 ? 1 : 0);

        JSONObject response = session.post(CLIP_PATH, body, "创建微信视频号异步裁剪任务");
        requireOk(response, "创建微信视频号异步裁剪任务");
        JSONObject data = response.getJSONObject("data");
        String clipKey = data == null ? null : data.getString("clipKey");
        String draftId = data == null ? null : data.getString("draftId");
        if (StringUtils.isAnyBlank(clipKey, draftId)) {
            throw new IllegalStateException("创建微信视频号异步裁剪任务失败: 缺少 clipKey/draftId");
        }
        return new ClipTicket(clipKey, draftId);
    }

    private void runContentPrecheck(PostText postText) {
        JSONObject postPreCheckInfo = new JSONObject(true);
        postPreCheckInfo.put("description", postText.description);
        // The captured request checks the optional shortTitle field. mpTitle is a separate field.
        postPreCheckInfo.put("shortTitle", "");
        JSONObject body = new JSONObject(true);
        body.put("appType", 1);
        body.put("checkType", 1);
        body.put("postPreCheckInfo", postPreCheckInfo);
        JSONObject response = session.post(PRECHECK_PATH, body, "执行微信视频号发布预检查");
        requireOk(response, "执行微信视频号发布预检查");
        JSONObject data = response.getJSONObject("data");
        if (data != null && data.getIntValue("blockLevel") != 0) {
            throw new IllegalStateException("微信视频号发布需要人工验证或内容确认: "
                    + StringUtils.defaultIfBlank(data.getString("wording"),
                    "blockLevel=" + data.getIntValue("blockLevel")));
        }
    }

    static JSONObject buildPublishBody(File videoFile,
                                       VideoInfo info,
                                       WechatVideoStorageClient.StorageUploadResult video,
                                       WechatVideoStorageClient.StorageUploadResult cover,
                                       ClipTicket clip,
                                       TraceInfo trace,
                                       PostText text,
                                       JSONObject location,
                                       WechatVideoWebSession.UploadContext context) {
        JSONObject body = new JSONObject(true);
        body.put("objectType", 0);
        body.put("longitude", 0);
        body.put("latitude", 0);
        body.put("feedLongitude", 0);
        body.put("feedLatitude", 0);
        body.put("originalFlag", 0);
        JSONArray topics = new JSONArray();
        topics.addAll(text.topics);
        body.put("topics", topics);
        body.put("isFullPost", 1);
        body.put("handleFlag", 2);
        body.put("videoClipTaskId", clip.draftId);
        body.put("traceInfo", buildTraceInfo(trace));

        JSONObject objectDesc = new JSONObject(true);
        objectDesc.put("mpTitle", text.title);
        objectDesc.put("description", text.description);
        objectDesc.put("extReading", new JSONObject(true));
        objectDesc.put("mediaType", 4);
        objectDesc.put("location", location == null ? new JSONObject(true) : location);
        JSONObject topic = new JSONObject(true);
        topic.put("finderTopicInfo", text.finderTopicInfo);
        objectDesc.put("topic", topic);
        objectDesc.put("event", new JSONObject(true));
        objectDesc.put("mentionedUser", new JSONArray());

        JSONObject media = new JSONObject(true);
        media.put("url", video.getDownloadUrl());
        media.put("fileSize", info.fileSize);
        media.put("thumbUrl", cover.getDownloadUrl());
        media.put("fullThumbUrl", cover.getDownloadUrl());
        media.put("mediaType", 4);
        media.put("videoPlayLen", info.duration);
        media.put("width", info.width);
        media.put("height", info.height);
        media.put("md5sum", video.getTaskId());
        if (info.width > info.height) {
            media.put("cardShowStyle", 2);
        }
        media.put("coverUrl", cover.getDownloadUrl());
        media.put("fullCoverUrl", cover.getDownloadUrl());
        if (context.getEnablePostShareCoverUrl() == 1) {
            media.put("shareCoverUrl", cover.getDownloadUrl());
        }
        media.put("urlCdnTaskId", clip.draftId);
        JSONArray mediaList = new JSONArray();
        mediaList.add(media);
        objectDesc.put("media", mediaList);
        objectDesc.put("member", new JSONObject(true));
        body.put("objectDesc", objectDesc);

        JSONObject report = new JSONObject(true);
        report.put("clipKey", clip.clipKey);
        report.put("draftId", clip.draftId);
        report.put("height", info.height);
        report.put("width", info.width);
        report.put("duration", info.duration);
        report.put("fileSize", videoFile.length());
        report.put("uploadCost", video.getUploadCostMillis());
        body.put("report", report);
        body.put("postFlag", 0);
        body.put("mode", 1);
        body.put("clientid", UUID.randomUUID().toString());
        return body;
    }

    static PostText buildPostText(WechatVideoMetaData metadata) {
        String rawTitle = metadata == null ? "" : StringUtils.defaultString(metadata.getTitle());
        String title = truncateWeighted(removeTitleWhitespace(rawTitle), 60);
        String desc = metadata == null ? "" : StringUtils.trimToEmpty(metadata.getDesc());
        rejectCdataTerminator(title);
        rejectCdataTerminator(desc);

        List<String> topics = new ArrayList<>();
        if (metadata != null && metadata.getTags() != null) {
            for (String rawTag : metadata.getTags()) {
                String tag = normalizeTopic(rawTag);
                if (StringUtils.isBlank(tag) || topics.contains(tag)) {
                    continue;
                }
                rejectCdataTerminator(tag);
                topics.add(tag);
            }
        }

        StringBuilder description = new StringBuilder(desc);
        List<String> finderValues = new ArrayList<>();
        if (StringUtils.isNotBlank(title)) {
            finderValues.add(cdata(title + "\n"));
        }
        if (StringUtils.isNotBlank(desc)) {
            finderValues.add(cdata(desc));
        }
        for (String tag : topics) {
            if (description.length() > 0) {
                description.append(' ');
                finderValues.add(cdata(" "));
            }
            description.append('#').append(tag);
            finderValues.add("<topic><![CDATA[#" + tag + "#]]></topic>");
        }

        String finderTopicInfo = "";
        if (!finderValues.isEmpty()) {
            StringBuilder xml = new StringBuilder("<finder><version>1</version><valuecount>")
                    .append(finderValues.size())
                    .append("</valuecount><style><at></at></style>");
            for (int index = 0; index < finderValues.size(); index++) {
                xml.append("<value").append(index).append('>')
                        .append(finderValues.get(index))
                        .append("</value").append(index).append('>');
            }
            finderTopicInfo = xml.append("</finder>").toString();
        }
        return new PostText(title, description.toString(), topics, finderTopicInfo);
    }

    static JSONObject buildLocation(JSONObject address) {
        JSONObject location = new JSONObject(true);
        if (address == null
                || (StringUtils.isBlank(address.getString("uid"))
                && StringUtils.isBlank(address.getString("city")))) {
            return location;
        }
        location.put("latitude", address.getDoubleValue("latitude"));
        location.put("longitude", address.getDoubleValue("longitude"));
        location.put("city", StringUtils.defaultString(address.getString("city")));
        if (StringUtils.isNotBlank(address.getString("name"))) {
            location.put("poiName", address.getString("name"));
        }
        if (StringUtils.isNotBlank(address.getString("fullAddress"))) {
            location.put("address", address.getString("fullAddress"));
        }
        location.put("poiClassifyId", StringUtils.defaultString(address.getString("uid")));
        return location;
    }

    static int[] targetSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("视频宽高必须大于 0");
        }
        double scale = width >= height
                ? Math.min(1920D / width, 1080D / height)
                : Math.min(1080D / width, 1920D / height);
        if (scale >= 1D) {
            return new int[]{width, height};
        }
        return new int[]{(int) Math.floor(width * scale), (int) Math.floor(height * scale)};
    }

    private static JSONObject buildTraceInfo(TraceInfo trace) {
        JSONObject traceInfo = new JSONObject(true);
        traceInfo.put("traceKey", trace.traceKey);
        traceInfo.put("uploadCdnStart", trace.uploadStart);
        traceInfo.put("uploadCdnEnd", trace.uploadEnd);
        return traceInfo;
    }

    private static VideoInfo detectVideoInfo(File videoFile) {
        VideoSizeDetectCmd size = new VideoSizeDetectCmd(videoFile.getAbsolutePath());
        size.execute(60);
        VideoDurationDetectCmd duration = new VideoDurationDetectCmd(videoFile.getAbsolutePath());
        duration.execute(60);
        if (!size.isNormalExit() || !duration.isNormalExit() || size.getWidth() <= 0
                || size.getHeight() <= 0 || duration.getDurationSeconds() <= 0) {
            throw new IllegalStateException("无法读取微信视频号待上传视频的宽高或时长: "
                    + videoFile.getAbsolutePath());
        }
        return new VideoInfo(size.getWidth(), size.getHeight(), duration.getDurationSeconds(),
                videoFile.length());
    }

    private static void validateFiles(File videoFile, File coverFile) {
        if (videoFile == null || !videoFile.isFile()) {
            throw new IllegalArgumentException("微信视频号待上传视频不存在: "
                    + (videoFile == null ? "null" : videoFile.getAbsolutePath()));
        }
        if (coverFile == null || !coverFile.isFile()) {
            throw new IllegalArgumentException("微信视频号首帧封面不存在: "
                    + (coverFile == null ? "null" : coverFile.getAbsolutePath()));
        }
    }

    private static void requireOk(JSONObject response, String operation) {
        if (response == null || response.getIntValue("errCode") != 0) {
            throw new IllegalStateException(operation + "失败: errCode="
                    + (response == null ? "null" : response.getIntValue("errCode"))
                    + ", errMsg=" + (response == null ? "" : response.getString("errMsg")));
        }
    }

    private static String removeTitleWhitespace(String value) {
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (codePoint != 0x200B && !Character.isWhitespace(codePoint)
                    && !Character.isSpaceChar(codePoint)) {
                result.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String truncateWeighted(String value, int maxWeight) {
        StringBuilder result = new StringBuilder();
        int weight = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int nextWeight = codePoint <= 0xFF ? 1 : 2;
            if (weight + nextWeight > maxWeight) {
                break;
            }
            result.appendCodePoint(codePoint);
            weight += nextWeight;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String normalizeTopic(String rawTag) {
        return StringUtils.trimToEmpty(rawTag).replace("#", "").trim();
    }

    private static String cdata(String value) {
        return "<![CDATA[" + value + "]]>";
    }

    private static void rejectCdataTerminator(String value) {
        if (value.contains("]]>")) {
            throw new IllegalArgumentException("微信视频号标题、描述或话题不能包含 ]]>");
        }
    }

    @Value
    static class VideoInfo {
        int width;
        int height;
        double duration;
        long fileSize;
    }

    @Value
    static class TraceInfo {
        String traceKey;
        long uploadStart;
        long uploadEnd;
    }

    @Value
    static class ClipTicket {
        String clipKey;
        String draftId;
    }

    @Value
    static class PostText {
        String title;
        String description;
        List<String> topics;
        String finderTopicInfo;
    }

    @Value
    public static class UploadResult {
        String clientId;
        String draftId;
        String videoUrl;
        String coverUrl;
    }
}
