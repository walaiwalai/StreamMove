package com.sh.engine.processor.checker;

import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;

@Component
@Slf4j
public class BiliRoomChecker extends AbstractRoomChecker {
    private static final String STREAMER_INFO_REFEX = "<script>window.__NEPTUNE_IS_MY_WAIFU__=(.*?)</script><script>";
    private static final String QUALITY_REGEX ="_\\d+(?=\\.m3u8\\?)";
    private static final Map<String, String> QUALITY_MAP = Maps.newHashMap();

    static {
        QUALITY_MAP.put("10000", "");
        QUALITY_MAP.put("400", "_4000");
        QUALITY_MAP.put("250", "_2500");
        QUALITY_MAP.put("150", "_1500");
    }

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(genRegPathByRegDate(date), date, roomUrl, true) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.BILI;
    }

//    private Map<String, String> buildHeader() {
//        return new HashMap<String, String>() {{
//            put("Referer", "https://live.bilibili.com");
//            put("User-Agent",
//                    "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36");
//        }};
//    }
//
//    private JSONObject fetchStreamerInfo(String roomUrl) {
//        Map<String, String> headers = buildHeader();
//        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getBiliCookies())) {
//            headers.put("Cookie", ConfigFetcher.getInitConfig().getBiliCookies());
//        }
//
//        Request.Builder requestBuilder = new Request.Builder()
//                .url(roomUrl)
//                .get()
//                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
//                .addHeader("Referer", "https://live.bilibili.com")
//                .addHeader("Accept-Language", "zh-CN,zh;q=0.9");
//        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getBiliCookies())) {
//            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getBiliCookies());
//        }
//
//        Response response = null;
//        try {
//            response = CLIENT.newCall(requestBuilder.build()).execute();
//            if (response.isSuccessful()) {
//                String resp = response.body().string();
//                String streamerInfoStr = RegexUtil.fetchMatchedOne(resp, STREAMER_INFO_REFEX);
//                return JSONObject.parseObject(streamerInfoStr);
//            } else {
//                String message = response.message();
//                String bodyStr = response.body() != null ? response.body().string() : null;
//                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
//                return null;
//            }
//        } catch (IOException e) {
//            log.error("query playlist success, roomUrl: {}", roomUrl, e);
//            return null;
//        }
//    }
//
//    private RecordStream fetchStream(JSONObject streamerObj) {
//        if (streamerObj == null) {
//            return null;
//        }
//        String anchorName = Optional.ofNullable(streamerObj.getJSONObject("roomInfoRes"))
//                .map(o -> o.getJSONObject("data"))
//                .map(o -> o.getJSONObject("anchor_info"))
//                .map(o -> o.getJSONObject("base_info"))
//                .map(o -> o.getString("uname"))
//                .orElse(null);
//        JSONObject playerUserInfo = streamerObj.getJSONObject("roomInitRes").getJSONObject("data").getJSONObject("playurl_info");
//        if (playerUserInfo == null) {
//            return null;
//        }
//
//        return RecordStream.builder()
//                .anchorName(anchorName)
//                .livingStreamUrl(fetchStreamUrl(playerUserInfo))
//                .build();
//
//    }
//
//    private String fetchStreamUrl(JSONObject playerUserInfo) {
//        // 获取m3u8的流
//        JSONObject streamDataObj = playerUserInfo.getJSONObject("playurl")
//                .getJSONArray("stream").getJSONObject(1)
//                .getJSONArray("format").getJSONObject(0)
//                .getJSONArray("codec").getJSONObject(0);
//        JSONArray acceptQn = streamDataObj.getJSONArray("accept_qn");
//        String baseUrl = streamDataObj.getString("base_url");
//        String host = streamDataObj.getJSONArray("url_info").getJSONObject(0).getString("host");
//        String extra = streamDataObj.getJSONArray("url_info").getJSONObject(0).getString("extra");
//
//        // 最少四个
//        int filledQnCount = Math.max(4 - acceptQn.size(), 0);
//        int lastQn = acceptQn.getInteger(acceptQn.size() - 1);
//        for (int i = 0; i < filledQnCount; i++) {
//            acceptQn.add(lastQn);
//        }
//        String qn = findQn(acceptQn);
//
//        // 进行替换
//        baseUrl = StringUtils.replacePattern(baseUrl, QUALITY_REGEX, QUALITY_MAP.get(qn));
//        extra = StringUtils.replace(extra, "&qn=0", "&qn=" + qn);
//        return host + baseUrl + extra;
//    }
//
//    private String findQn(JSONArray acceptQn) {
//        String quality = ConfigFetcher.getInitConfig().getQuality();
//        if (StringUtils.equals(quality, "原画")) {
//            return acceptQn.getString(0);
//        } else if (StringUtils.equals(quality, "超清")) {
//            return acceptQn.getString(1);
//        } else if (StringUtils.equals(quality, "高清")) {
//            return acceptQn.getString(2);
//        } else if (StringUtils.equals(quality, "标清")) {
//            return acceptQn.getString(3);
//        } else {
//            return acceptQn.getString(0);
//        }
//    }
}
