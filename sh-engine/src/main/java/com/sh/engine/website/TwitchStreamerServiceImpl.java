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

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Component
@Slf4j
public class TwitchStreamerServiceImpl extends AbstractStreamerService {
    private static final String GQL_ENDPOINT = "https://gql.twitch.tv/gql";
    private static final String VALID_URL_BASE = "(?:https?://)?(?:(?:www|go|m)\\.)?twitch\\.tv/([0-9_a-zA-Z]+)";
    private static volatile boolean AUTH_EXPIRE_STATUS = false;
    private static final int RETRY_COUNT = 2;
    private static final String USER_QUERY = "query query($channel_name:String!) { user(login: $channel_name) { stream { id title type previewImageURL(width: 0,height: 0) playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { signature value } } } }";

    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), VALID_URL_BASE);
        JSONObject params = new JSONObject();
        JSONObject channelObj = new JSONObject();
        channelObj.put("channel_name", channelName);
        params.put("query", USER_QUERY);
        params.put("variables", channelObj);
        JSONObject respObj = doRequest(params);
        JSONObject userObj = respObj.getJSONObject("data").getJSONObject("user");
        if (userObj == null) {
            log.error("no user info");
            return null;
        }
        JSONObject streamObj = userObj.getJSONObject("stream");
        if (streamObj == null || !StringUtils.equals(streamObj.getString("type"), "live")) {
            return null;
        }

        Map<String, String> query = new HashMap<>();
        query.put("player", "twitchweb");
        query.put("p", String.valueOf(new Random().nextInt(9000000) + 1000000));
        query.put("allow_source", "true");
        query.put("allow_audio_only", "true");
        query.put("allow_spectre", "false");
        query.put("fast_bread", "true");
        query.put("sig", streamObj.getJSONObject("playbackAccessToken").getString("signature"));
        query.put("token", streamObj.getJSONObject("playbackAccessToken").getString("value"));

        String rawStreamUrl = null;
        try {
            rawStreamUrl = String.format("https://usher.ttvnw.net/api/channel/hls/%s.m3u8?%s",
                    URLEncoder.encode(channelName, "UTF-8"),
                    URLEncoder.encode(query.toString(), "UTF-8"));
        } catch (Exception e) {
            log.error("gen twitch stream url error, query: {}", JSON.toJSONString(query), e);
            return null;
        }

        return LivingStreamer.builder()
                .roomTitle(streamObj.getString("title"))
                .streamUrl(rawStreamUrl)
                .build();
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCH;
    }

    private JSONObject doRequest(JSONObject params) {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Content-Type", "text/plain;charset=UTF-8");
        headers.put("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko");
        String twitchCookie = ConfigFetcher.getInitConfig().getTwitchCookies();
        if (!AUTH_EXPIRE_STATUS && StringUtils.isNotBlank(twitchCookie)) {
            headers.put("Authorization", "OAuth " + twitchCookie);
        }

        String responseBody = HttpClientUtil.sendPost(GQL_ENDPOINT, headers, params);
        JSONObject respObj = JSON.parseObject(responseBody);
        if (respObj.containsKey("error")) {
            String error = respObj.getString("error");
            if ("Unauthorized".equals(error)) {
                AUTH_EXPIRE_STATUS = true;
                log.error("twitch cookies has expired, error: {}", error);
                // 递归调用以重新尝试请求
                return doRequest(params);
            }
        }
        return respObj;
    }

    /**
     * curl -X POST \
     * 'https://gql.twitch.tv/gql' \
     * -H 'Content-Type: text/plain;charset=UTF-8' \
     * -H 'Client-ID: kimne78kx3ncx6brgo4mv6wki5h1ko' \
     * -H 'Authorization: OAuth lkr3unq6wyvlti1tf1husoevb8uq20' \
     * -d '{
     * "query": "query query($channel_name:String!) { user(login: $channel_name) { stream { id title type previewImageURL(width: 0,height: 0) playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { signature value } } } }",
     * "variables": {"channel_name": "your_channel_name"}
     * }'
     *
     * @param args
     */
    public static void main(String[] args) {
//        System.setProperty("proxyHost", "127.0.0.1"); // 代理服务器地址，这里是本地主机
//        System.setProperty("http.proxyHost", "127.0.0.1"); // 代理服务器地址，这里是本地主机
//        System.setProperty("https.proxyHost", "127.0.0.1"); // 代理服务器地址，这里是本地主机
//        System.setProperty("proxyPort", "10809"); // 代理服务器端口号
//        System.setProperty("http.proxyPort", "10809"); // 代理服务器端口号
//        System.setProperty("https.proxyPort", "10809"); // 代理服务器端口号

//        TwitchStreamerServiceImpl service = new TwitchStreamerServiceImpl();
//        LivingStreamer livingStreamer = service.isRoomOnline(StreamerConfig.builder()
//                .roomUrl("https://www.twitch.tv/tommy181933")
//                .build());
//        System.out.println(livingStreamer.getStreamUrl());
        String s = HttpClientUtil.sendGet("https://www.twitch.tv/thijs");
//        System.out.println(s);
    }
}
