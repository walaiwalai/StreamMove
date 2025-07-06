package com.sh.message.controller;

import com.google.common.base.Preconditions;
import com.sh.config.manager.CacheManager;
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
        msgSendService.sendText("接受到测试通知，content=" + content);
        log.info("receive content: {}, from: {}", content, from);
        Preconditions.checkArgument(validSign(sign, timestamp), "sign error");

        parseLiveOn(content, from);
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
        String title = paramMap.get("title");
        String msg = paramMap.get("msg");
        String streamerName = null;


        LiveOnReceiveModel liveOnReceiveModel = new LiveOnReceiveModel();
        liveOnReceiveModel.setFrom(from);
        liveOnReceiveModel.setReceiveTime(paramMap.get("receiveTime"));
        liveOnReceiveModel.setStreamerName(streamerName);
        return liveOnReceiveModel;
    }
}
