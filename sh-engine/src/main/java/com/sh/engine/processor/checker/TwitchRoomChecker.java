package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.util.RegexUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class TwitchRoomChecker extends AbstractRoomChecker {
    private static final String GQL_ENDPOINT = "https://gql.twitch.tv/gql";
    private static final String VALID_URL_BASE = "(?:https?://)?(?:(?:www|go|m)\\.)?twitch\\.tv/([0-9_a-zA-Z]+)";

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchLivingRecord(streamerConfig);
        } else {
            return fetchLatestRecord(streamerConfig);
        }
    }

    private Recorder fetchLivingRecord(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, roomUrl) : null;
    }

    private Recorder fetchLatestRecord(StreamerConfig streamerConfig) {
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

        return new StreamLinkRecorder(date, videoUrl);
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
}
