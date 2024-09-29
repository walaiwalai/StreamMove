package com.sh.message.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sh.message.enums.CorpWxEventTypeEnum;
import com.sh.message.model.wecom.WeComConfig;
import com.sh.message.model.wecom.CorpWxEventReceiverModel;
import com.sh.message.model.wecom.aes.AesException;
import com.sh.message.model.wecom.aes.WXBizMsgCrypt;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2024 09 29 11 29
 **/
@Component
@Slf4j
public class WeComMsgEventManager {
    private static final String EVENT_INFO_TYPE = "InfoType";
    private static final String EVENT_MSG_TYPE = "MsgType";
    private static final String EVENT_EVENT = "Event";
    private static final String EVENT_CONTENT = "Content";


    @Resource
    private WeComConfig weComConfig;

    public String processEvent(JSONObject decrpEventJson) {
        processWxEvent(decrpEventJson);
        return "success";
    }

    public String verifyOk(CorpWxEventReceiverModel model, WXBizMsgCrypt crypt) {
        try {
            String resStr = crypt.VerifyURL(model.getMsg_signature(), model.getTimestamp(), model.getNonce(), model.getEchostr());
            log.info("sucess verify weixin event callback, in: {}, res: {}", JSON.toJSONString(model), resStr);
            return resStr;
        } catch (AesException e) {
            log.error("error to verify callback", e);
            return null;
        }
    }

    private void processWxEvent(JSONObject wxEventJson) {
        if (wxEventJson == null) {
            log.info("weixin event empty, will ignore");
            return;
        }

        String msgContent = wxEventJson.getString(EVENT_CONTENT);
        if (StringUtils.isBlank(msgContent)) {
            return;
        }
        log.info("receive question in corpWx msgContent : {}", msgContent);
    }

    private CorpWxEventTypeEnum extractType(JSONObject decrpEvent) {
        JSONObject msgObj = decrpEvent.getJSONObject(EVENT_MSG_TYPE);
        JSONObject eventObj = decrpEvent.getJSONObject(EVENT_EVENT);
        if (msgObj != null && eventObj != null) {
            return CorpWxEventTypeEnum.getEventType(decrpEvent.getString(EVENT_EVENT));
        }
        return null;
    }
}
