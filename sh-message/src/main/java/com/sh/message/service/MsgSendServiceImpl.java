package com.sh.message.service;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.PictureFileUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * @Author caiwen
 * @Date 2023 12 24 22 44
 **/
@Component
@Slf4j
public class MsgSendServiceImpl implements MsgSendService {
    private static final String WECOM_WEBHOOK_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=";

    @Value("${wecom.webhook.secret}")
    private String weComSecret;

    @Override
    public void sendText(String message) {
        sendWeComTestMsg(message);
    }

    @Override
    public void sendImage(File imageFile) {
        sendWeComImageMsg(imageFile);
    }

    private void sendWeComTestMsg(String message) {
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
            String resp = OkHttpClientUtil.execute(request);
            log.info("send msg success, msg: {}, resp: {}", message, resp);
        } catch (Exception e) {
            log.error("send weCom msg error, msg: {}", message, e);
        }
    }

    private void sendWeComImageMsg(File imageFile) {
        MediaType mediaType = MediaType.parse("application/json");

        Map<String, String> msg = Maps.newHashMap();
        msg.put("base64", PictureFileUtil.fileToBase64(imageFile));
        msg.put("md5", PictureFileUtil.calculateFileMD5(imageFile));

        Map<String, Object> params = Maps.newHashMap();
        params.put("msgtype", "image");
        params.put("image", msg);
        RequestBody requestBody = RequestBody.create(mediaType, JSON.toJSONString(params));
        String url = WECOM_WEBHOOK_URL + weComSecret;
        Request request = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build();

        // 发送请求并处理响应
        try {
            String resp = OkHttpClientUtil.execute(request);
            log.info("send image success, resp: {}", resp);
        } catch (Exception e) {
            log.error("send weCom image error, path: {}", imageFile.getAbsolutePath(), e);
        }
    }
}
