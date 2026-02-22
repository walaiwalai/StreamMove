package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.danmu.DanmakuRecorder;
import com.sh.engine.processor.recorder.stream.StreamRecorder;
import com.sh.engine.processor.recorder.stream.StreamUrlStreamRecorder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.Request;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 快手存在同一个ip检测风险，采用主动获取推送开播消息进行检测
 *
 * @Author caiwen
 * @Date 2025 05 29 22 24
 **/
@Component
@Slf4j
public class KuaishouRoomChecker extends AbstractRoomChecker {
    // 定义正则表达式模式
    private static final Pattern INITIAL_STATE_PATTERN =
            Pattern.compile("<script>window.__INITIAL_STATE__=(.*?);\\(function\\(\\)\\{var s;");
    private static final Pattern PLAY_LIST_PATTERN =
            Pattern.compile("(\\{\"liveStream\".*?),\"gameInfo");


    @Override
    public StreamRecorder getStreamRecorder(StreamerConfig streamerConfig) {
        log.warn("{} is online by kuaishou receiving message", streamerConfig.getName());
        String roomUrl = streamerConfig.getRoomUrl();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0");
        headers.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");

        String cookies = ConfigFetcher.getInitConfig().getKuaishouCookies();
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", cookies);
        }
        Request request = new Request.Builder()
                .url(roomUrl)
                .get()
                .headers(Headers.of(headers))
                .build();
        String resp = OkHttpClientUtil.execute(request);
        return parseStreamData(resp, streamerConfig);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.KUAISHOU;
    }

    private StreamRecorder parseStreamData(String htmlStr, StreamerConfig streamerConfig) {
        Matcher initialStateMatcher = INITIAL_STATE_PATTERN.matcher(htmlStr);
        if (!initialStateMatcher.find()) {
            return null;
        }

        String jsonStr = initialStateMatcher.group(1);
        Matcher playListMatcher = PLAY_LIST_PATTERN.matcher(jsonStr);
        if (!playListMatcher.find()) {
            return null;
        }

        String playListStr = playListMatcher.group(1) + "}";
        log.warn("kuai shou playlistObj: {}", playListStr);

        JSONObject playlistObj = JSON.parseObject(playListStr);
        if (playlistObj.containsKey("errorType")) {
            log.error("get error kuaishou stream failed, errorType: {}", playlistObj.getString("errorType"));
            return null;
        }
        if (!playlistObj.containsKey("liveStream")) {
            return null;
        }

        JSONObject liveStreamObj = playlistObj.getJSONObject("liveStream");
        if (liveStreamObj == null) {
            log.error("IP banned. Please change device or network.");
            return null;
        }

        JSONObject playUrlsObj = liveStreamObj.getJSONObject("playUrls");
        if (playUrlsObj == null) {
            return null;
        }
        JSONArray represents;
        if (playUrlsObj.containsKey("h264")) {
            JSONObject urlObj = playUrlsObj.getJSONObject("h264");
            if (!urlObj.containsKey("adaptationSet")) {
                return null;
            }
            represents = urlObj.getJSONObject("adaptationSet").getJSONArray("representation");
        } else {
            represents = playlistObj.getJSONArray("playUrls").getJSONObject(0).getJSONObject("adaptationSet").getJSONArray("representation");
        }

        // 获取直播地址
        if (represents.getJSONObject(0).containsKey("bitrate")) {
            List<JSONObject> urlObjs = represents.stream()
                    .sorted(Comparator.comparingInt(x -> ((JSONObject) x).getInteger("bitrate")).reversed())
                    .map(x -> (JSONObject) x)
                    .collect(Collectors.toList());
            return new StreamUrlStreamRecorder(new Date(), streamerConfig.getRoomUrl(), getType().getType(), urlObjs.get(0).getString("url"));

//            if (urlObjs.get(0).getInteger("bitrate") <= 6000) {
//                return new StreamUrlRecorder(new Date(), urlObjs.get(0).getString("url"));
//            } else {
//                return new StreamUrlRecorder(new Date(), urlObjs.get(1).getString("url"));
//            }
        } else {
            JSONObject lastUrlObj = represents.getJSONObject(represents.size() - 1);
            return new StreamUrlStreamRecorder(new Date(), streamerConfig.getRoomUrl(), getType().getType(), lastUrlObj.getString("url"));
        }
    }
}
