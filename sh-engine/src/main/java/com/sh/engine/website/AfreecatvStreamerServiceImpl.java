package com.sh.engine.website;

import com.alibaba.fastjson.JSONObject;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.model.record.LivingStreamer;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 *
 * @author caiWen
 * @date 2023/2/18 21:24
 */
@Component
@Slf4j
public class AfreecatvStreamerServiceImpl extends AbstractStreamerService {
    private static final String USER_URL = "http://api.m.afreecatv.com/broad/a/watch";
    private static final String BID_REGEX = "(?<=com/)([^/]+)$";

    @Override
    public LivingStreamer isRoomOnline(StreamerInfo streamerInfo) {
        String roomUrl = streamerInfo.getRoomUrl();
        String bid = RegexUtil.fetchMatchedOne(roomUrl, BID_REGEX);

        JSONObject userRespObj = fetchUserInfo(bid);
        if (userRespObj == null) {
            return null;
        }

        if (userRespObj.getInteger("result") != 1) {
            return null;
        }

        String anchorName = userRespObj.getJSONObject("data").getString("user_nick");
        String boardNo = userRespObj.getJSONObject("data").getString("broad_no");
        String hlsAuthenticationKey = userRespObj.getJSONObject("data").getString("hls_authentication_key");
        String viewUrl = fetchCdnUrl(boardNo);
        String m3u8Url = StringUtils.isNotBlank(viewUrl) ? viewUrl + "?aid=" + hlsAuthenticationKey : null;
        return LivingStreamer.builder()
                .recordUrl(m3u8Url)
                .anchorName(anchorName)
                .build();
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.AFREECA_TV;
    }

    private JSONObject fetchUserInfo(String bid) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("bj_id", bid)
                .addFormDataPart("agent", "web")
                .addFormDataPart("confirm_adult", "true")
                .addFormDataPart("player_type", "webm")
                .addFormDataPart("mode", "live")
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(USER_URL)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                .addHeader("Referer", "https://m.afreecatv.com/")
                .addHeader("Content-Type", "application/x-www-form-urlencoded");

        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                log.info("query user info success, resp: {}", resp);
                return JSONObject.parseObject(resp);
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query user info error, bid: {}", bid, e);
            return null;
        }
    }

    private String fetchCdnUrl(String boardNo) {
        MediaType mediaType = MediaType.parse("application/x-www-form-urlencoded");
        Map<String, String> params = new HashMap<>();
        params.put("return_type", "gcp_cdn");
        params.put("use_cors", "false");
        params.put("cors_origin_url", "play.afreecatv.com");
        params.put("broad_key", boardNo + "-common-master-hls");
        params.put("time", "8361.086329376785");

        String apiUrl = "http://livestream-manager.afreecatv.com/broad_stream_assign.html?" + HttpClientUtil.encodeParams(params);
        Request.Builder requestBuilder = new Request.Builder()
                .url(apiUrl)
                .get()
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                .addHeader("Referer", "https://play.afreecatv.com/oul282/249469582")
                .addHeader("Content-Type", "application/x-www-form-urlencoded");
        if (StringUtils.isNotBlank(ConfigFetcher.getInitConfig().getAfreecaTvCookies())) {
            requestBuilder.addHeader("Cookie", ConfigFetcher.getInitConfig().getAfreecaTvCookies());
        }
        Response response = null;
        try {
            response = CLIENT.newCall(requestBuilder.build()).execute();
            if (response.isSuccessful()) {
                String resp = response.body().string();
                log.info("user living info success, resp: {}", resp);
                return JSONObject.parseObject(resp).getString("view_url");
            } else {
                String message = response.message();
                String bodyStr = response.body() != null ? response.body().string() : null;
                log.error("query user living info failed, message: {}, body: {}", message, bodyStr);
                return null;
            }
        } catch (IOException e) {
            log.error("query user living info error, boardNo: {}", boardNo, e);
            return null;
        }
    }

    public static void main(String[] args) {
        AfreecatvStreamerServiceImpl service = new AfreecatvStreamerServiceImpl();
        LivingStreamer s = service.isRoomOnline(StreamerInfo.builder().roomUrl("https://play.afreecatv.com/hongdda").build());
        System.out.println(s.getRecordUrl());
    }
}
