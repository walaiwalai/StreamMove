package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.RegexUtil;
import com.sh.engine.util.WebsiteStreamUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考：https://github.com/ihmily/DouyinLiveRecorder
 *
 * @author caiWen
 * @date 2023/1/23 13:39
 */
@Component
@Slf4j
public class HuyaStreamerServiceImpl extends AbstractStreamerService {
    private static final String STREAM_DATA_REGEX = "stream:\\s*(\\{\"data\".*?),\"iWebDefaultBitRate\"";
    private static final String QUALITY_REGEX = "(?<=264_)\\d+";


    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0");
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");

        String huyaCookies = ConfigFetcher.getInitConfig().getHuyaCookies();
        if (StringUtils.isNotBlank(huyaCookies)) {
            headers.put("Cookie", huyaCookies);

        }

        // 获取直播地址中的流信息
        String resp = HttpClientUtil.sendGet(streamerConfig.getRoomUrl(), headers, null, false);
        String jsonStr = RegexUtil.fetchMatchedOne(resp, STREAM_DATA_REGEX);
        if (StringUtils.isBlank(jsonStr)) {
            return null;
        }

        jsonStr += "}";
        JSONObject streamObj = JSON.parseObject(jsonStr);
        JSONObject gameLiveInfoObj = streamObj.getJSONArray("data").getJSONObject(0).getJSONObject("gameLiveInfo");
        JSONArray streamInfoObj = streamObj.getJSONArray("data").getJSONObject(0).getJSONArray("gameStreamInfoList");
        String anchorName = gameLiveInfoObj.getString("nick");
        if (streamInfoObj.isEmpty()) {
            return null;
        }

        // 在线
        JSONObject selectCdn = streamInfoObj.getJSONObject(0);
        String sFlvUrl = selectCdn.getString("sFlvUrl");
        String sStreamName = selectCdn.getString("sStreamName");
        String sFlvUrlSuffix = selectCdn.getString("sFlvUrlSuffix");
        String sHlsUrl = selectCdn.getString("sHlsUrl");
        String sHlsUrlSuffix = selectCdn.getString("sHlsUrlSuffix");
        String sFlvAntiCode = selectCdn.getString("sFlvAntiCode");

        String newAntiCode = WebsiteStreamUtil.getHuyaAntiCode(sFlvAntiCode, sStreamName);
        String aliFlv = sFlvUrl + "/" + sStreamName + "." + sFlvUrlSuffix + "?" + newAntiCode + "&ratio=";
        String[] qualityChoices = StringUtils.split(sFlvAntiCode, "&exsphd=");
        if (qualityChoices.length > 0) {
            List<String> qualities = RegexUtil.getMatchList(qualityChoices[qualityChoices.length - 1], QUALITY_REGEX, false);
            String quality = ConfigFetcher.getInitConfig().getQuality();
            if (StringUtils.equals(quality, "原画")) {
                aliFlv += qualities.get(qualities.size() - 1);
            } else {
                aliFlv += qualities.get(qualities.size() - 2);
            }
        }
        return LivingStreamer.builder()
                .anchorName(anchorName)
                .streamUrl(aliFlv)
                .build();
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.HUYA;
    }

    public static void main(String[] args) {
        HuyaStreamerServiceImpl huyaStreamerService = new HuyaStreamerServiceImpl();
        LivingStreamer res = huyaStreamerService.isRoomOnline(StreamerConfig.builder()
                .roomUrl("https://www.huya.com/991111")
                .build());
        System.out.println(res.getStreamUrl());
    }
}
