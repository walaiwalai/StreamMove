package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * @Author caiwen
 * @Date 2024 03 17 14 18
 **/
@Component
@Slf4j
public class ChzzkStreamerServiceImpl extends AbstractStreamerService {
    private static final String CHANNEL_REGEX = "/(\\p{XDigit}{32})$";
    private static final String API_URL = "https://api.chzzk.naver.com/service/v2/channels/{channel_name}/live-detail";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Whale/3.23.214.17 Safari/537.36";

    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        String chzzkCookies = ConfigFetcher.getInitConfig().getChzzkCookies();
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), CHANNEL_REGEX);
        String detailUrl = API_URL.replace("{channel_name}", channelName);

        Map<String, String> headers = Maps.newHashMap();
        headers.put("User-Agent", USER_AGENT);
        if (StringUtils.isNotBlank(chzzkCookies)) {
            headers.put("Cookie", chzzkCookies);
        }
        String resp = HttpClientUtil.sendGet(detailUrl, headers, null, false);
        JSONObject respObj = JSONObject.parseObject(resp);
        JSONObject contentObj = respObj.getJSONObject("content");
        if (!StringUtils.equals(contentObj.getString("status"), "OPEN")) {
            // 没有直播
            return null;
        }
        JSONObject playInfoObj = JSON.parseObject(contentObj.getString("livePlaybackJson"));
        String streamUrl = playInfoObj.getJSONArray("media").getJSONObject(0).getString("path");
        return LivingStreamer.builder()
                .streamUrl(streamUrl)
                .anchorName(contentObj.getJSONObject("channel").getString("channelName"))
                .roomTitle(contentObj.getString("liveTitle"))
                .build();

    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.CHZZK;
    }

    public static void main(String[] args) {
        ChzzkStreamerServiceImpl service = new ChzzkStreamerServiceImpl();
        LivingStreamer livingStreamer = service.isRoomOnline(StreamerConfig.builder()
                .roomUrl("https://chzzk.naver.com/b628d1039a84ecc703804e17acee2eb3")
                .build());
        System.out.println(livingStreamer.getStreamUrl());
    }
}
