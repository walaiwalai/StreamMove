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
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class TwitchRoomChecker extends AbstractRoomChecker {
    private static final String GQL_ENDPOINT = "https://gql.twitch.tv/gql";
    private static final String VALID_URL_BASE = "(?:https?://)?(?:(?:www|go|m)\\.)?twitch\\.tv/([0-9_a-zA-Z]+)";
    private static final String USER_QUERY = "query query($channel_name:String!) { user(login: $channel_name) { stream { id title type previewImageURL(width: 0,height: 0) playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { signature value } } } }";

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
        return isLiving ? new StreamLinkRecorder(genRegPathByRegDate(date), date, roomUrl) : null;
    }

    private Recorder fetchLatestRecord(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), VALID_URL_BASE);

        // 获取最近视频
        VideoShelvesItem videoItem = null;
        try {
            videoItem = findLatestVideoItem(channelName);
        } catch (Exception e) {
            return null;
        }
        // 发布时间
        Instant instant = Instant.parse(videoItem.getPublishedAt());
        Date date = Date.from(instant);
        boolean isNewTs = checkVodIsNew(streamerConfig, date);
        if (!isNewTs || DateUtils.addMinutes(date, 30).getTime() > System.currentTimeMillis()) {
            // 延迟0.5小时
            return null;
        }

        // 最近视频链接
        String videoUrl = "https://www.twitch.tv/videos/" + videoItem.getId();

        return new StreamLinkRecorder(genRegPathByRegDate(date), date, videoUrl);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCH;
    }

    private VideoShelvesItem findLatestVideoItem( String channelName ) {
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
        JSONArray items = JSON.parseArray(resp).getJSONObject(0)
                .getJSONObject("data")
                .getJSONObject("user")
                .getJSONObject("videoShelves")
                .getJSONArray("edges")
                .getJSONObject(0)
                .getJSONObject("node")
                .getJSONArray("items");
        List<VideoShelvesItem> videoShelvesItems = JSON.parseArray(items.toJSONString(), VideoShelvesItem.class);
        return videoShelvesItems.stream()
                .max(Comparator.comparing(video -> Instant.parse(video.getPublishedAt())))
                .orElse(null);
    }

    @Data
    private static class VideoShelvesItem {
        private String id;
        private String publishedAt;
    }
}
