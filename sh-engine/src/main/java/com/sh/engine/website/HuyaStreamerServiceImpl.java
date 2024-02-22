package com.sh.engine.website;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.RegexUtil;
import com.sh.engine.util.WebsiteStreamUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 参考：https://github.com/ihmily/DouyinLiveRecorder
 * @author caiWen
 * @date 2023/1/23 13:39
 */
@Component
@Slf4j
public class HuyaStreamerServiceImpl extends AbstractStreamerService {
    private static final String STREAM_REGEX = "(?<=(\"gameStreamInfoList\":)).*?](?=(}]))";
    private static final String QUALITY_REGEX = "(?<=264_)\\d+";
    @Override
    public LivingStreamer isRoomOnline(StreamerConfig streamerConfig) {
        String resp = HttpUtil.get(streamerConfig.getRoomUrl());
        List<String> matchList = RegexUtil.getMatchList(resp, STREAM_REGEX, false);
        if (matchList.size() >= 1) {
            String gameStreamInfo = matchList.get(0);
            JSONArray streamInfoObj = JSON.parseArray(gameStreamInfo);
            if (streamInfoObj.isEmpty()) {
                return null;
            }

            log.info("{} is online", streamerConfig.getName());
            JSONObject selectCdn = streamInfoObj.getJSONObject(1);
            String sFlvUrl = selectCdn.getString("sFlvUrl");
            String sStreamName = selectCdn.getString("sStreamName");
            String sFlvUrlSuffix = selectCdn.getString("sFlvUrlSuffix");
            String sHlsUrl = selectCdn.getString("sHlsUrl");
            String sHlsUrlSuffix = selectCdn.getString("sHlsUrlSuffix");
            String sFlvAntiCode = selectCdn.getString("sFlvAntiCode");
            String[] qualityChoices = StringUtils.split(sFlvAntiCode, "&exsphd=");
            Map<String, String> paramMap = Maps.newHashMap();
            Arrays.stream(StringUtils.split(sFlvAntiCode, "&"))
                    .forEach(p -> {
                        String[] kv = p.split("=");
                        paramMap.put(kv[0], kv[1]);
                    });

            // sFlvAntiCode中只要wsSecret和wsTime
//            String aliFlv = sFlvUrl + "/" + sStreamName + "." + sFlvUrlSuffix + "?" + "wsSecret=" + paramMap.get("wsSecret") + "&wsTime=" + paramMap.get("wsTime") + "&ratio=";
            String aliFlv = sFlvUrl + "/" + sStreamName + "." + sFlvUrlSuffix + "?" + sFlvAntiCode + "&ratio=";
            if (qualityChoices.length > 0) {
                List<String> qualities = RegexUtil.getMatchList(qualityChoices[qualityChoices.length - 1], QUALITY_REGEX, false);
                String quality = ConfigFetcher.getInitConfig().getQuality();
                if (StringUtils.equals(quality, "原画")) {
                    aliFlv += qualities.get(qualities.size() - 1);
                } else {
                    aliFlv += qualities.get(qualities.size() - 2);
                }
            }

            return LivingStreamer.builder().streamUrl(aliFlv).build();
        } else {
            return null;
        }
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
