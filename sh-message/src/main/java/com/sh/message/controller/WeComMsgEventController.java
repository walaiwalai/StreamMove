package com.sh.message.controller;

import cn.hutool.json.XML;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.message.manager.WeComMsgEventManager;
import com.sh.message.model.wecom.WeComConfig;
import com.sh.message.model.wecom.CorpWxEventReceiverModel;
import com.sh.message.model.wecom.aes.AesException;
import com.sh.message.model.wecom.aes.WXBizMsgCrypt;
import com.sh.message.util.ResponseUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Controller
@RequestMapping("/weCom/msg")
public class WeComMsgEventController {
    @Resource
    private WeComConfig weComConfig;
    @Resource
    private WeComMsgEventManager weComMsgEventManager;

    @RequestMapping(value = "/event", method = {RequestMethod.POST, RequestMethod.GET})
    @ResponseBody
    public void accept(@ModelAttribute CorpWxEventReceiverModel model, HttpServletRequest request, HttpServletResponse response) {
        try {
            WXBizMsgCrypt crypt = new WXBizMsgCrypt(weComConfig.getEventToken(), weComConfig.getEncodingAesKey(), weComConfig.getCorpId());
            // 验证链接正确
            if (RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())) {
                String resStr = weComMsgEventManager.verifyOk(model, crypt);
                ResponseUtil.writeText(response, resStr);
                return;
            }

            // 事件处理
            if (RequestMethod.POST.name().equalsIgnoreCase(request.getMethod())) {
                String eventXml = ResponseUtil.readText(request);
                JSONObject decryptJson = decryptMsg(crypt, model, eventXml);
                String code = weComMsgEventManager.processEvent(decryptJson);
                ResponseUtil.writeText(response, code);
            }
        } catch (Exception e) {
            log.error("fail to process weixin event", e);
        }
    }

    @RequestMapping(value = "/test", method = {RequestMethod.POST, RequestMethod.GET})
    @ResponseBody
    public String accept() {
        return "hah";
    }


    private JSONObject decryptMsg(WXBizMsgCrypt crypt, CorpWxEventReceiverModel model, String eventXml) {
        if (StringUtils.isBlank(eventXml)) {
            return null;
        }
        try {
            String decryXml = crypt.DecryptMsg(model.getMsg_signature(), model.getTimestamp(), model.getNonce(), eventXml);
            cn.hutool.json.JSONObject xml = XML.toJSONObject(decryXml).getJSONObject("xml");
            JSONObject xmlObj = xml.toBean(JSONObject.class);
            log.info("sucess decrpt xml: {}", decryXml);
            return xmlObj;
        } catch (AesException e) {
            log.error("error to decrypt weixin event: {}", eventXml);
            throw new RuntimeException(e);
        }
    }
}
