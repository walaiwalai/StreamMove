package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.sh.config.manager.CacheManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamLinkStreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.VodM3U8StreamRecorder;
import com.sh.engine.util.RegexUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class TwitchRoomChecker extends AbstractRoomChecker {
    @Resource
    private CacheManager cacheManager;
    private static final String GQL_ENDPOINT = "https://gql.twitch.tv/gql";
    private static final String VALID_URL_BASE = "(?:https?://)?(?:(?:www|go|m)\\.)?twitch\\.tv/([0-9_a-zA-Z]+)";

    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchLivingRecord(streamerConfig);
        } else {
            if (CollectionUtils.isNotEmpty(streamerConfig.getCertainVodUrls())) {
                return fetchCertainRecords(streamerConfig);
            } else {
                return fetchLatestRecord(streamerConfig);
            }
        }
    }

    @Override
    public DanmakuRecorder getDanmakuRecorder(StreamerConfig streamerConfig) {
        return null;
    }

    private StreamRecorder fetchLivingRecord(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkStreamRecorder(date, getType().getType(), roomUrl) : null;
    }

    private StreamRecorder fetchLatestRecord(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), VALID_URL_BASE);

        // 获取最近视频
        VideoShelvesItem videoItem = findLatestVideoItem(channelName);
        if (videoItem == null) {
            return null;
        }

        // 发布时间
        Date date = Date.from(Instant.parse(videoItem.getPublishedAt()));
        boolean isNewTs = checkVodIsNew(streamerConfig, date);
        if (!isNewTs) {
            return null;
        }

        // 最近视频链接
        String videoUrl = "https://www.twitch.tv/videos/" + videoItem.getId();

        return new StreamLinkStreamRecorder(date, getType().getType(), videoUrl);
    }

    private StreamRecorder fetchCertainRecords(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), VALID_URL_BASE);

        String key = "certain_keys_" + streamerConfig.getName();
        String videoId = null;
        for (String vodUrl : streamerConfig.getCertainVodUrls()) {
            String vid = vodUrl.split("videos/")[1];
            String finishFlag = cacheManager.getHash(key, vid, new TypeReference<String>() {
            });
            if (StringUtils.isBlank(finishFlag)) {
                videoId = vid;
                break;
            }
        }
        if (videoId == null) {
            return null;
        }

        // 2. 解析切片成链接格式
        String curVodUrl = "https://www.twitch.tv/videos/" + videoId;
        JSONObject curVod = fetchCurVodInfo(channelName, videoId);
        if (curVod == null) {
            return null;
        }

        Date date = curVod.getDate("publishedAt");
        Map<String, String> extra = new HashMap<>();
        extra.put("finishKey", key);
        extra.put("finishField", videoId);

        return new VodM3U8StreamRecorder(date, getType().getType(), curVodUrl, extra);
    }


    private JSONObject fetchCurVodInfo(String channelName, String videoId) {
        JSONObject varObj = new JSONObject();
        varObj.put("channelLogin", channelName);
        varObj.put("videoID", videoId);

        JSONObject extObj = new JSONObject();
        extObj.put("persistedQuery", ImmutableMap.of("version", 1, "sha256Hash", "45111672eea2e507f8ba44d101a61862f9c56b11dee09a15634cb75cb9b9084d"));

        JSONObject jsonObj = new JSONObject();
        jsonObj.put("operationName", "VideoMetadata");
        jsonObj.put("extensions", extObj);
        jsonObj.put("variables", varObj);

        Request request = new Request.Builder()
                .url(GQL_ENDPOINT)
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(Lists.newArrayList(jsonObj))))
                .addHeader("Content-Type", "text/plain;charset=UTF-8")
                .addHeader("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        log.info("fetch cur vod info resp: {}", resp);
        try {
            return JSON.parseArray(resp).getJSONObject(0)
                    .getJSONObject("data")
                    .getJSONObject("video");
        } catch (Exception e) {
            return null;
        }
    }


    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCH;
    }

    private VideoShelvesItem findLatestVideoItem(String channelName) {
        JSONObject varObj = new JSONObject();
        varObj.put("channelLogin", channelName);
        varObj.put("first", 5);
        varObj.put("includePreviewBlur", false);

        JSONObject extObj = new JSONObject();
        extObj.put("persistedQuery", ImmutableMap.of("version", 1, "sha256Hash", "eea6c7a6baaa6ee60825f469c943cfda7e7c2415c01c64d7fd1407d172e82a7b"));

        JSONObject jsonObj = new JSONObject();
        jsonObj.put("operationName", "ChannelVideoShelvesQuery");
        jsonObj.put("extensions", extObj);
        jsonObj.put("variables", varObj);

        Request request = new Request.Builder()
                .url(GQL_ENDPOINT)
                .post(RequestBody.create(MediaType.parse("application/json"), JSON.toJSONString(Lists.newArrayList(jsonObj))))
                .addHeader("Content-Type", "text/plain;charset=UTF-8")
                .addHeader("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        try {
            JSONArray items = new JSONArray();
            JSONArray edges = JSON.parseArray(resp).getJSONObject(0)
                    .getJSONObject("data")
                    .getJSONObject("user")
                    .getJSONObject("videoShelves")
                    .getJSONArray("edges");
            for (int i = 0; i < edges.size(); i++) {
                JSONObject edgeObj = edges.getJSONObject(i);
                JSONObject nodeObj = edgeObj.getJSONObject("node");
                if (!"ALL_VIDEOS".equals(nodeObj.getString("type"))) {
                    continue;
                }
                items = nodeObj.getJSONArray("items");
            }
            List<VideoShelvesItem> videoShelvesItems = JSON.parseArray(items.toJSONString(), VideoShelvesItem.class);
            VideoShelvesItem latestItem = videoShelvesItems.stream()
                    .max(Comparator.comparing(video -> Instant.parse(video.getPublishedAt())))
                    .orElse(null);

            // 针对twitch，在直播时也会产生当前直播的录像，等直播完成再开始录播
            // 封面如果在处理中，说明还在进行直播
            return latestItem != null && !latestItem.getPreviewThumbnailURL().contains("processing") ? latestItem : null;
        } catch (Exception e) {
            return null;
        }

    }

    @Data
    private static class VideoShelvesItem {
        private String id;
        private String publishedAt;
        private String previewThumbnailURL;
    }

    public static void main(String[] args) {
        // 设置代理服务器信息
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "10808");

        // 如果是 HTTPS 代理，需要额外设置
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "10808");

        TwitchRoomChecker twitchRoomChecker = new TwitchRoomChecker();
        JSONObject jsonObject = twitchRoomChecker.fetchCurVodInfo("hookd", "2581084030");
        System.out.println(jsonObject.getDate("publishedAt"));
    }
}
