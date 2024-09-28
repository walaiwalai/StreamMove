package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.Recorder;
import com.sh.engine.model.record.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

@Component
@Slf4j
public class TwitchStreamerServiceImpl extends AbstractStreamerService {
    private static final String GQL_ENDPOINT = "https://gql.twitch.tv/gql";
    private static final String VALID_URL_BASE = "(?:https?://)?(?:(?:www|go|m)\\.)?twitch\\.tv/([0-9_a-zA-Z]+)";
    private static volatile boolean AUTH_EXPIRE_STATUS = false;
    private static final int RETRY_COUNT = 2;
    private static final String USER_QUERY = "query query($channel_name:String!) { user(login: $channel_name) { stream { id title type previewImageURL(width: 0,height: 0) playbackAccessToken(params: { platform: \"web\", playerBackend: \"mediaplayer\", playerType: \"site\" }) { signature value } } } }";

//    @Override
//    public RecordStream isRoomOnline(StreamerConfig streamerConfig) {
//        if (BooleanUtils.isTrue(streamerConfig.isRecordWhenOnline())) {
//            return RecordStream.builder()
//                    .roomTitle(streamObj.getString("title"))
//                    .livingStreamUrl(rawStreamUrl)
//                    .build();
//        } else {
//            return fetchTsUploadInfo(streamerConfig);
//        }


//        String channelName = RegexUtil.fetchMatchedOne(streamerConfig.getRoomUrl(), VALID_URL_BASE);
//        JSONObject params = new JSONObject();
//        JSONObject channelObj = new JSONObject();
//        channelObj.put("channel_name", channelName);
//        params.put("query", USER_QUERY);
//        params.put("variables", channelObj);
//        JSONObject respObj = doRequest(params);
//        JSONObject userObj = respObj.getJSONObject("data").getJSONObject("user");
//        if (userObj == null) {
//            log.error("no user info");
//            return null;
//        }
//        JSONObject streamObj = userObj.getJSONObject("stream");
//        if (streamObj == null || !StringUtils.equals(streamObj.getString("type"), "live")) {
//            return null;
//        }
//
//        String rawStreamUrl = UrlBuilder.create()
//                .setScheme("https")
//                .setHost("usher.ttvnw.net")
//                .addPath("/api")
//                .addPath("/channel")
//                .addPath("/hls")
//                .addPath(channelName + ".m3u8")
//                .addQuery("player", "twitchweb")
//                .addQuery("p", String.valueOf(new Random().nextInt(9000000) + 1000000))
//                .addQuery("allow_source", "true")
//                .addQuery("allow_audio_only", "true")
//                .addQuery("allow_spectre", "false")
//                .addQuery("fast_bread", "true")
//                .addQuery("sig", streamObj.getJSONObject("playbackAccessToken").getString("signature"))
//                .addQuery("token", streamObj.getJSONObject("playbackAccessToken").getString("value"))
//                .build();
//        return RecordStream.builder()
//                .roomTitle(streamObj.getString("title"))
//                .livingStreamUrl(rawStreamUrl)
//                .build();
//    }

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(genRegPathByRegDate(date), date, roomUrl) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.TWITCH;
    }

//    private JSONObject doRequest(JSONObject params) {
//        Map<String, String> headers = Maps.newHashMap();
//        headers.put("Content-Type", "text/plain;charset=UTF-8");
//        headers.put("Client-ID", "kimne78kx3ncx6brgo4mv6wki5h1ko");
//        String twitchCookie = ConfigFetcher.getInitConfig().getTwitchCookies();
//        if (!AUTH_EXPIRE_STATUS && StringUtils.isNotBlank(twitchCookie)) {
//            headers.put("Authorization", "OAuth " + twitchCookie);
//        }
//
//        String responseBody = HttpClientUtil.sendPost(GQL_ENDPOINT, headers, params);
//        JSONObject respObj = JSON.parseObject(responseBody);
//        if (respObj.containsKey("error")) {
//            String error = respObj.getString("error");
//            if ("Unauthorized".equals(error)) {
//                AUTH_EXPIRE_STATUS = true;
//                log.error("twitch cookies has expired, error: {}", error);
//                // 递归调用以重新尝试请求
//                return doRequest(params);
//            }
//        }
//        return respObj;
//    }
}
