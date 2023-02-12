package com.sh.engine.manager.website;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.utils.HttpClientUtil;
import com.sh.engine.StreamChannelTypeEnum;
import com.sh.engine.util.JavaScriptUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * @author caiWen
 * @date 2023/2/4 23:28
 */
@Component
@Slf4j
public class DouyuStreamerServiceImpl extends AbstractStreamerService {
    private static final String RID_REGEX = "(?<=www.douyu.com/).+";
    private static final String DIGIT_REGEX = "([0-9])+";
    private static final String ROOM_LOOP_INFO_URL = "https://www.douyu.com/wgapi/live/liveweb/getRoomLoopInfo?rid=%s";
    public static final String HOME_H5_URL = "https://www.douyu.com/swf_api/homeH5Enc?rids=%s";
    private final static String ENCRYPT_METHOD = "ub98484234";
    private final static String H5_PLAY_URL
            = "https://www.douyu.com/lapi/live/getH5Play/%s?cdn=tct-h5&did=%s&iar=0&ive=0&rate=0&v=%s&tt=%s&sign=%s";


    @Override
    public String isRoomOnline(StreamerInfo streamerInfo) {
        String rid = RegexUtil.fetchFirstMatchedOne(streamerInfo.getRoomUrl(), RID_REGEX, false);
        if (StringUtils.isBlank(rid)) {
            rid = RegexUtil.fetchFirstMatchedOne(streamerInfo.getRoomUrl(), DIGIT_REGEX, false);
        }
        if (StringUtils.isBlank(rid)) {
            log.error("roomUrl is illegal for douyu, roomUrl: {}", streamerInfo.getRoomUrl());
            return null;
        }

        String roomLoopInfoUrl = String.format(ROOM_LOOP_INFO_URL, rid);
        String resp1 = HttpClientUtil.sendGet(roomLoopInfoUrl);
        JSONObject roomInfoObj = JSON.parseObject(resp1);
        System.out.println("----" + resp1 + "-------");
        if (StringUtils.isBlank(roomInfoObj.getJSONObject("data").getString("vid"))) {
            // vid不为空表示录像
            return null;
        }

        String signAlgoResp = HttpClientUtil.sendGet(String.format(HOME_H5_URL, rid));
        JSONObject signAlgoRespObj = JSON.parseObject(signAlgoResp);
        if (!StringUtils.equals(signAlgoRespObj.getString("error"), "0")) {
            log.error("Get douyu sign algorithm fail, resp: {}", signAlgoResp);
            return null;
        }

        // 构造sign进行解析
        String deviceId = UUID.randomUUID().toString().replaceAll("-", "");
        String signAlgScript = signAlgoRespObj.getJSONObject("data").getString("room" + rid);
        String requestTime = String.valueOf(System.currentTimeMillis() / 1000L);
        String decodeRes = JavaScriptUtil.execJs(signAlgScript, ENCRYPT_METHOD, rid, deviceId, requestTime);
        String sign = RegexUtil.fetchFirstMatchedOne(decodeRes, "(?<=sign=)(.+)", false);
        String version = RegexUtil.fetchFirstMatchedOne(decodeRes, "(?<=v=)(.+)(?=(&did))", false);

        // 获取h5视频信息
        String h5Url = String.format(H5_PLAY_URL, rid, deviceId, version, requestTime, sign);
        String resp = HttpClientUtil.sendPost(h5Url, buildHeader(rid), Maps.newHashMap());
        JSONObject h5RespObj = JSONObject.parseObject(resp);
        if (!StringUtils.equals(h5RespObj.getString("error"), "0")) {
            log.error("Douyu h5Play info fetch error, msg: {}", h5RespObj.getString("msg"));
            return null;
        }
        JSONObject h5RespData = h5RespObj.getJSONObject("data");
        return h5RespData.getString("rtmp_url") + "/" + h5RespData.getString("rtmp_live");
    }

    private Map<String, String> buildHeader(String rid) {
        return new HashMap<String, String>() {{
            put("Accept", "application/json, text/plain, */*");
            put("Content-Type", "application/x-www-form-urlencoded");
            put("Origin", "https://www.douyu.com");
            put("Referer", "https://www.douyu.com/" + rid);
            put("X-Requested-With", "XMLHttpRequest");
            put("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.88"
                            + " Safari/537.36");
        }};
    }

    @Override
    public StreamChannelTypeEnum getType() {
        return StreamChannelTypeEnum.DOUYU;
    }

    public static void main(String[] args) {
        DouyuStreamerServiceImpl douyuStreamerService = new DouyuStreamerServiceImpl();
        String res = douyuStreamerService.isRoomOnline(StreamerInfo.builder()
                .roomUrl("https://www.douyu.com/8925391")
                .build());
        System.out.println(res);
    }
}
