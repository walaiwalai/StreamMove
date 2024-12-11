package com.sh.engine.processor.checker;

import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamLinkRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 参考：https://github.com/ihmily/DouyinLiveRecorder
 *
 * @author caiWen
 * @date 2023/1/23 13:39
 */
@Component
@Slf4j
public class HuyaRoomChecker extends AbstractRoomChecker {
//    private static final String STREAM_DATA_REGEX = "stream:\\s*(\\{\"data\".*?),\"iWebDefaultBitRate\"";
//    private static final String QUALITY_REGEX = "(?<=264_)\\d+";


//    @Override
//    public RecordStream isRoomOnline(StreamerConfig streamerConfig) {
//        Map<String, String> headers = new HashMap<>();
//        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0");
//        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
//        headers.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
//
//        String huyaCookies = ConfigFetcher.getInitConfig().getHuyaCookies();
//        if (StringUtils.isNotBlank(huyaCookies)) {
//            headers.put("Cookie", huyaCookies);
//
//        }
//
//        // 获取直播地址中的流信息
//        String resp = HttpClientUtil.sendGet(streamerConfig.getRoomUrl(), headers, null, false);
//        String jsonStr = RegexUtil.fetchMatchedOne(resp, STREAM_DATA_REGEX);
//        if (StringUtils.isBlank(jsonStr)) {
//            return null;
//        }
//
//        jsonStr += "}";
//        JSONObject streamObj = JSON.parseObject(jsonStr);
//        JSONObject gameLiveInfoObj = streamObj.getJSONArray("data").getJSONObject(0).getJSONObject("gameLiveInfo");
//        JSONArray streamInfoObj = streamObj.getJSONArray("data").getJSONObject(0).getJSONArray("gameStreamInfoList");
//        String anchorName = gameLiveInfoObj.getString("nick");
//        if (streamInfoObj.isEmpty()) {
//            return null;
//        }
//
//        // 在线
//        JSONObject selectCdn = streamInfoObj.getJSONObject(0);
//        String sFlvUrl = selectCdn.getString("sFlvUrl");
//        String sStreamName = selectCdn.getString("sStreamName");
//        String sFlvUrlSuffix = selectCdn.getString("sFlvUrlSuffix");
//        String sHlsUrl = selectCdn.getString("sHlsUrl");
//        String sHlsUrlSuffix = selectCdn.getString("sHlsUrlSuffix");
//        String sFlvAntiCode = selectCdn.getString("sFlvAntiCode");
//
//        String newAntiCode = WebsiteStreamUtil.getHuyaAntiCode(sFlvAntiCode, sStreamName);
//        String aliFlv = sFlvUrl + "/" + sStreamName + "." + sFlvUrlSuffix + "?" + newAntiCode + "&ratio=";
//        String[] qualityChoices = StringUtils.split(sFlvAntiCode, "&exsphd=");
//        if (qualityChoices.length > 1) {
//            List<String> qualities = RegexUtil.getMatchList(qualityChoices[qualityChoices.length - 1], QUALITY_REGEX, false);
//            Collections.reverse(qualities);
//            while (qualities.size() < 4) {
//                qualities.add(qualities.get(qualities.size() - 1));
//            }
//
//            String quality = ConfigFetcher.getInitConfig().getQuality();
//            Map<String, String> qualityMap = new HashMap<>();
//            qualityMap.put("原画", qualities.get(0));
//            qualityMap.put("超清", qualities.get(1));
//            qualityMap.put("高清", qualities.get(2));
//            qualityMap.put("标清", qualities.get(3));
//            aliFlv += qualityMap.getOrDefault(quality, "原画");
//        }
//        return RecordStream.builder()
//                .anchorName(anchorName)
//                .livingStreamUrl(aliFlv)
//                .build();
//    }

    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String roomUrl = streamerConfig.getRoomUrl();
        boolean isLiving = checkIsLivingByStreamLink(roomUrl);

        Date date = new Date();
        return isLiving ? new StreamLinkRecorder(date, roomUrl, true) : null;
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.HUYA;
    }
}
