package com.sh.message.util;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.Method;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2023 08 20 09 24
 **/
@Slf4j
public class CorpWxApi {
    private static final String WEIXIN_HOST = "https://qyapi.weixin.qq.com";
    private static final String TOKEN_URI = "/cgi-bin/gettoken";
    public static String getAccessToken(String corpId, String secret) {
        if (StringUtils.isBlank(corpId) || StringUtils.isBlank(secret)) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }

        String url = String.format("https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s", corpId, secret);
        String result = HttpRequest.get(url)
                .method(Method.GET)
                .execute()
                .body();
        return JSON.parseObject(result).getString("access_token");
    }

    public static void sendTextMsgToUser(String accessToken, String agentId, List<String> userIds, String text) {
        Map<String, Object> textMap = Maps.newHashMap();
        textMap.put("content", text);

        Map<String, Object> mainMap = Maps.newHashMap();
        mainMap.put("msgtype", "text");
        mainMap.put("touser", StringUtils.join(userIds, "|"));
        mainMap.put("agentid", agentId);
        mainMap.put("text", textMap);

        String url = String.format(" https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=%s", accessToken);
        String result = HttpRequest.get(url)
                .method(Method.POST)
                .body(JSONUtil.toJsonStr(mainMap))
                .addHeaders(ImmutableMap.of("Content-Type", "application/json"))
                .execute()
                .body();
        log.info("send msg resp: {}", result);
    }
}
