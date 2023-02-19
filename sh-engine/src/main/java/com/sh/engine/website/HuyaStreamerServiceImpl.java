package com.sh.engine.website;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.sh.config.model.config.StreamerInfo;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author caiWen
 * @date 2023/1/23 13:39
 */
@Component
@Slf4j
public class HuyaStreamerServiceImpl extends AbstractStreamerService {
    private static final String STREAM_REGEX = "(?<=(\"gameStreamInfoList\":)).*?](?=(}]))";
    @Override
    public String isRoomOnline(StreamerInfo streamerInfo) {
        String resp = HttpUtil.get(streamerInfo.getRoomUrl());
        List<String> matchList = RegexUtil.getMatchList(resp, STREAM_REGEX, false);
        if (matchList.size() >= 1) {
            String gameStreamInfo = matchList.get(0);
            JSONArray streamInfoObj = JSON.parseArray(gameStreamInfo);
            if (streamInfoObj.isEmpty()) {
                return null;
            }

            log.info("{} is online", streamerInfo.getName());
            String aliFlv = streamInfoObj.getJSONObject(0).get("sFlvUrl") + "/"
                    + streamInfoObj.getJSONObject(0).get("sStreamName") + ".flv?"
                    + streamInfoObj.getJSONObject(0).get("sFlvAntiCode");
            return StringUtils.replace(aliFlv, "&amp;", "");
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
        String s = huyaStreamerService.isRoomOnline(StreamerInfo.builder().roomUrl("https://www.huya.com/gushouyu").build());
        String s2 =
                huyaStreamerService.isRoomOnline(StreamerInfo.builder().roomUrl("https://www.huya.com/chenzihao").build());
        System.out.println(s);
        System.out.println(s2);
    }
}
