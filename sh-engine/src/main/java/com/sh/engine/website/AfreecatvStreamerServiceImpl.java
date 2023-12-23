package com.sh.engine.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author caiWen
 * @date 2023/2/18 21:24
 */
@Component
@Slf4j
public class AfreecatvStreamerServiceImpl extends AbstractStreamerService {
    private static final String BID_REGEX = "(?<=(com/))(.*)(?=(/))";
    private static final String CHANNEL_API_URL = "https://live.afreecatv.com/afreeca/player_live_api.php?bjid=%s";
    private static final String STREAM_INFO_SUFFIX_URL
            = "/broad_stream_assign.html?return_type=%s&use_cors=true&cors_origin_url=play.afreecatv"
            + ".com&broad_key=%s-common-%s-hls";
    private static final String CHANNEL_POST_DATA
            = "bid=%s&bno=%s&type=live&pwd=&player_type=html5&stream_type=common&quality=HD&mode=landing&from_api=0";
    private static final String CHANNEL_QUALITY_POST_DATA
            = "bid=%s&bno=%s&type=pwd&pwd=&player_type=html5&stream_type=common&quality=%s&mode"
            + "=landing&from_api=0";
    private static final List<String> QUALITYS = Lists.newArrayList("master", "original", "hd", "sd");
//    private static final List<String> QUALITYS = Lists.newArrayList("master");

    @Override
    public LivingStreamer isRoomOnline(StreamerInfo streamerInfo) {
        String roomUrl = streamerInfo.getRoomUrl();
        String bid = RegexUtil.fetchFirstMatchedOne(roomUrl, BID_REGEX);
        if (StringUtils.isBlank(bid)) {
            log.error("Afreecatv Room not exists, roomUrl: {}.", roomUrl);
            return null;
        }


        JSONObject channelObj = getChannelInfo(bid, roomUrl);
        if (!checkChannelValid(channelObj)) {
            return null;
        }

        String broadcast = channelObj.getString("BNO");
        String rmd = channelObj.getString("RMD");
        String cdn = channelObj.getString("CDN");
        if (StringUtils.isBlank(broadcast) || StringUtils.isBlank(rmd) || StringUtils.isBlank(cdn)) {
            log.error("AFREECATV no match results:Maybe the roomid is error,or this room is not open!");
            return null;
        }

        // 获取streamUrl
        return LivingStreamer.builder().recordUrl(fetchAvailableStreamUrl(roomUrl, broadcast, bid, cdn, rmd)).build();
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.AFREECA_TV;
    }

    private String fetchAvailableStreamUrl(String roomUrl, String broadcast, String bid, String cdn, String rmd) {
        String hlsStream = null;
        for (String quality : QUALITYS) {
            hlsStream = getHlsStream(roomUrl, broadcast, bid, quality, cdn, rmd);
            if (StringUtils.isNotBlank(hlsStream)) {
                break;
            }
        }
        return hlsStream;
    }

    private String getHlsStream(String roomUrl, String broadcast, String bid, String quality, String cdn, String rmd) {
        String qualityPostStr = String.format(CHANNEL_QUALITY_POST_DATA, bid, broadcast, quality);
        String res = HttpClientUtil.sendPost(String.format(CHANNEL_API_URL, bid), buildHeaders(roomUrl), qualityPostStr);
        JSONObject channelObj = JSONObject.parseObject(res).getJSONObject("CHANNEL");
        if (!Objects.equals(channelObj.getInteger("RESULT"), 1)) {
            return null;
        }

        String aid = channelObj.getString("AID");
        String streamInfoUrl = rmd + String.format(STREAM_INFO_SUFFIX_URL, cdn, broadcast, quality);
        String streamInfoStr = HttpClientUtil.sendGet(streamInfoUrl, buildHeaders(roomUrl));
        if (StringUtils.isBlank(streamInfoStr)) {
            return null;
        }
        JSONObject streamInfoObj = JSONObject.parseObject(streamInfoStr);
        return streamInfoObj.getString("view_url") + "?aid=" + aid;

    }

    private boolean checkChannelValid(JSONObject channelObj) {
        if (channelObj == null) {
            return false;
        }
        if (StringUtils.equals(channelObj.getString("BPWD"), "Y")) {
            log.error("AFREECATV Stream is Password-Protected");
            return false;
        } else if (Objects.equals(channelObj.getInteger("RESULT"), -6)) {
            log.error("AFREECATV Login required");
            return false;
        } else if (!Objects.equals(channelObj.getInteger("RESULT"), 1)) {
            log.error("AFREECATV No match results:Maybe the roomid is error, or this room is not open!");
            return false;
        }
        return true;
    }

    private Map<String, String> buildHeaders(String roomUrl) {
        Map<String, String> headers = Maps.newHashMap();
        headers.put("Proxy-Connection", "keep-alive");
        headers.put("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147"
                        + ".105 Safari/537.36");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "*/*");
        headers.put("Origin", "http://play.afreecatv.com");
        headers.put("Referer", roomUrl);
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Accept-Language", "zh,zh-TW;q=0.9,en-US;q=0.8,en;q=0.7,zh-CN;q=0.6,ru;q=0.5");
        return headers;
    }

    private JSONObject getChannelInfo(String bid, String roomUrl) {
        String postUrl = String.format(CHANNEL_API_URL, bid);
        String postStr = String.format(CHANNEL_POST_DATA, bid, "");
        String res = HttpClientUtil.sendPost(postUrl, buildHeaders(roomUrl), postStr);
        JSONObject resObj = JSON.parseObject(res);
        if (resObj == null) {
            return null;
        }
        return resObj.getJSONObject("CHANNEL");
    }
}
