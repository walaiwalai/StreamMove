package com.sh.engine.processor.checker;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.constant.StreamChannelTypeEnum;
import com.sh.engine.processor.recorder.Recorder;
import com.sh.engine.processor.recorder.StreamUrlRecorder;
import com.sh.engine.util.RegexUtil;
import com.sh.engine.util.UrlUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author : caiwen
 * @Date: 2025/1/28
 */
@Component
@Slf4j
public class XhsRoomChecker extends AbstractRoomChecker {
    @Override
    public Recorder getStreamRecorder(StreamerConfig streamerConfig) {
        String xhsCookies = ConfigFetcher.getInitConfig().getXhsCookies();
        String url = streamerConfig.getRoomUrl();

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))");
        headers.put("xy-common-params", "platform=iOS&sid=session.1722166379345546829388");
        headers.put("referer", "https://app.xhs.cn/");

        String userId = RegexUtil.fetchMatchedOne(url, "/user/profile/(.*?)(?=/|\\?|$)");
        String appUrl = "https://live-room.xiaohongshu.com/api/sns/v1/live/user_status?user_id_list=" + userId;

        Request.Builder reBuilder = new Request.Builder()
                .url(appUrl)
                .get()
                .addHeader("User-Agent", "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))")
                .addHeader("xy-common-params", "platform=iOS&sid=session.1722166379345546829388")
                .addHeader("referer", "https://app.xhs.cn/");
        if (StringUtils.isNotBlank(xhsCookies)) {
            reBuilder.addHeader("Cookie", xhsCookies);
        }

        String resp = OkHttpClientUtil.execute(reBuilder.build());

        JSONObject respObj = JSON.parseObject(resp);
        if (CollectionUtils.isEmpty(respObj.getJSONArray("data"))) {
            return null;
        }

        // 开播了
        String liveLink = respObj.getJSONArray("data").getJSONObject(0).getString("live_link");
        String anchorName = UrlUtil.getParams(liveLink, "host_nickname");
        String flvUrl = UrlUtil.getParams(liveLink, "flvUrl");
        String roomId = flvUrl.split("live/")[1].split("\\.")[0];
        flvUrl = "http://live-source-play.xhscdn.com/live/" + roomId + ".flv";
        String m3u8Url = flvUrl.replace(".flv", ".m3u8");

        return new StreamUrlRecorder(new Date(), getType().getType(), flvUrl);
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.XHS;
    }
}
