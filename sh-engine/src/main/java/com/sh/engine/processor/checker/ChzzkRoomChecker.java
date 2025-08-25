package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import com.sh.engine.processor.recorder.VodM3u8Recorder;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

/**
 * 采用streamLink支持直播和录像
 *
 * @Author caiwen
 * @Date 2024 03 17 14 18
 **/
@Component
@Slf4j
public class ChzzkRoomChecker extends AbstractRoomChecker {
    private static final String CHANNEL_REGEX = "/(\\p{XDigit}{32})$";
    private static final String LATEST_VIDEO_URL = "https://api.chzzk.naver.com/service/v1/channels/{channel_name}/videos?sortType=LATEST&pagingType=PAGE&page=0&size=24&publishDateAt=&videoType=";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Whale/3.23.214.17 Safari/537.36";

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
            return fetchOnlineStream(streamerConfig);
        } else {
            return fetchReplayStream(streamerConfig);
        }
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.CHZZK;
    }

    private Recorder fetchOnlineStream(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), CHANNEL_REGEX);
        String roomUrl = "https://chzzk.naver.com/live/" + channelName;
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, getType().getType(), roomUrl) : null;
    }

    private Recorder fetchReplayStream(StreamerConfig streamerConfig) {
        // 1.获取最近的videoNo
        JSONObject videoObj = getLatestVideoNo(streamerConfig);
        if (videoObj == null) {
            return null;
        }

        // 时长小于10分钟太短不要（还在直播就已经有直播录像了）
        if (videoObj.getInteger("duration") < 60 * 10) {
            return null;
        }

        // 2 最新发布时间, 已经录过跳过
        Date date = DateUtil.covertStr2Date(videoObj.getString("publishDate"), DateUtil.YYYY_MM_DD_HH_MM_SS);
        boolean isNewTs = checkVodIsNew(streamerConfig, date);
        if (!isNewTs) {
            return null;
        }

        return new VodM3u8Recorder(
                date, getType().getType(),
                "https://chzzk.naver.com/video/" + videoObj.getString("videoNo")
        );
    }

    private JSONObject getLatestVideoNo(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), CHANNEL_REGEX);

        // 获取最近发布的一个视频，获取videoId
        String latestVideoUrl = LATEST_VIDEO_URL.replace("{channel_name}", channelName);
        String latestResp = HttpClientUtil.sendGet(latestVideoUrl, buildHeaders(), null, false);
        JSONObject respObj = JSONObject.parseObject(latestResp);
        JSONObject contentObj = respObj.getJSONObject("content");

        if (contentObj.getInteger("totalCount") < 1) {
            return null;
        } else {
            return contentObj.getJSONArray("data").getJSONObject(0);
        }
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("User-Agent", USER_AGENT);
        return headers;
    }
}
