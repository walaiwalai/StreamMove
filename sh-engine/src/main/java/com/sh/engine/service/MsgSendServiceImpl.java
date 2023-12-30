package com.sh.engine.service;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * @Author caiwen
 * @Date 2023 12 24 22 44
 **/
@Component
@Slf4j
public class MsgSendServiceImpl implements MsgSendService {
    private static final OkHttpClient client = new OkHttpClient().newBuilder().build();
    private static final String WECOM_WEBHOOK_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";

    @Override
    public void send(String message) {
        sendWeComMsg(message);
    }

    private void sendWeComMsg(String message) {
        String weComSecret = ConfigFetcher.getInitConfig().getWeComSecret();
        if (StringUtils.isBlank(weComSecret)) {
            return;
        }

        MediaType mediaType = MediaType.parse("application/json");

        Map<String, String> msg = Maps.newHashMap();
        msg.put("content", message);

        Map<String, Object> params = Maps.newHashMap();
        params.put("msgtype", "text");
        params.put("text", msg);
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
        String url = WECOM_WEBHOOK_URL + weComSecret;
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build();

        // 发送请求并处理响应
        try {
            client.newCall(request).execute();
            log.info("send msg success, msg: {}", message);
        } catch (IOException e) {
            log.error("send weCom msg error, msg: {}", message, e);
        }
    }
}
