package com.sh.message.controller;

import com.alibaba.fastjson.JSON;
import com.google.common.base.Preconditions;
import com.sh.config.manager.CacheManager;
import com.sh.message.constant.MessageConstant;
import com.sh.message.model.message.LiveOnReceiveModel;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 自建webhook，用于接受主动开播的消息
 *
 * @Author caiwen
 * @Date 2025 07 06 11 34
 **/
@Slf4j
@Controller
@RequestMapping("/message")
public class MessageReceiveController {
    @Value("${message.receive.secret}")
    private String secret;
    @Resource
    private CacheManager cacheManager;
    @Resource
    private MsgSendService msgSendService;

    @RequestMapping(value = "/liveOn", method = {RequestMethod.POST})
    @ResponseBody
    public String accept(@RequestParam String sign,
                         @RequestParam String timestamp,
                         @RequestParam String from,
                         @RequestParam String content) {
        log.info("receive content: {}, from: {}", content, from);
        Preconditions.checkArgument(validSign(sign, timestamp), "sign error");

        LiveOnReceiveModel liveOnReceiveModel = parseLiveOn(content, from);
        if (liveOnReceiveModel != null) {
            msgSendService.sendText("接受到开播的APP消息，content: " + content + ", from: " + from);
            String platform = liveOnReceiveModel.getFrom();
            String streamerName = liveOnReceiveModel.getStreamerName();

            // 具体哪个主播来波
            cacheManager.setHash(MessageConstant.LIVE_ON_HASH_PREFIX_KEY + platform, streamerName,
                    JSON.toJSONString(liveOnReceiveModel), 20, TimeUnit.MINUTES);

            // 当前平台有人开播
            cacheManager.set(MessageConstant.PLAT_PUSH_LIVE_PREFIX_KEY + platform, "1", 20, TimeUnit.MINUTES);
        }
        return "ok";
    }


    private boolean validSign(String sign, String timestamp) {
        String stringToSign = timestamp + "\n" + secret;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String signCal = URLEncoder.encode(new String(Base64.encodeBase64(signData)), "UTF-8");
            return StringUtils.equals(sign, signCal);
        } catch (Exception e) {
            return false;
        }
    }

    private LiveOnReceiveModel parseLiveOn(String content, String from) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String[] paramPairs = content.split(";");
        Map<String, String> paramMap = Arrays.stream(paramPairs)
                .map(param -> param.split("="))
                .collect(Collectors.toMap(param -> param[0], param -> param[1]));
        if (StringUtils.equals(from, "com.smile.gifmaker")) {
            return parseFromKS(paramMap);
        }
        if (StringUtils.equals(from, "com.soop.live")) {
            return parseFromSoop(paramMap);
        }
        return null;
    }

    private LiveOnReceiveModel parseFromKS(Map<String, String> paramMap) {
        String msg = paramMap.get("msg");
        if (!msg.contains("正在直播")) {
            return null;
        }
        String streamerName = paramMap.get("title").split("【")[0];
        LiveOnReceiveModel liveOnReceiveModel = new LiveOnReceiveModel();
        liveOnReceiveModel.setFrom(MessageConstant.KUAI_SHOU_PLATFORM);
        liveOnReceiveModel.setReceiveTime(paramMap.get("receiveTime"));
        liveOnReceiveModel.setStreamerName(streamerName);
        return liveOnReceiveModel;
    }

    private LiveOnReceiveModel parseFromSoop(Map<String, String> paramMap) {
        LiveOnReceiveModel liveOnReceiveModel = new LiveOnReceiveModel();
        liveOnReceiveModel.setFrom(MessageConstant.SOOP_PLATFORM);
        liveOnReceiveModel.setReceiveTime(paramMap.get("receiveTime"));
        return liveOnReceiveModel;
    }
}
