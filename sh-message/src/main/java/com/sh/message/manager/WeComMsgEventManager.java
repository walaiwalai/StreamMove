package com.sh.message.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import com.sh.config.manager.CacheManager;
import com.sh.message.enums.CorpWxEventTypeEnum;
import com.sh.message.model.wecom.WeComConfig;
import com.sh.message.model.wecom.CorpWxEventReceiverModel;
import com.sh.message.model.wecom.aes.AesException;
import com.sh.message.model.wecom.aes.WXBizMsgCrypt;
import com.sh.message.service.MessageProcessHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Map;

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

    private Map<String, MessageProcessHandler> msgHandlerMap = Maps.newHashMap();

    @Resource
    private ApplicationContext applicationContext;

    @PostConstruct
    private void init() {
        Map<String, MessageProcessHandler> beansOfType = applicationContext.getBeansOfType(MessageProcessHandler.class);
        beansOfType.forEach((key, value) -> msgHandlerMap.put(value.getType(), value));
    }

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

        // 后置处理
        doPostProcess(msgContent);
    }

    /**
     * 根据消息进行后置处理
     * @param msgContent
     */
    private void doPostProcess(String msgContent) {
        if (!StringUtils.startsWith(msgContent, "#")) {
            return;
        }
        String[] splited = StringUtils.split(msgContent," ");
        if (splited.length < 2) {
            return;
        }
        String msgType = splited[0].substring(1);
        String content = splited[1];
        for (String type : msgHandlerMap.keySet()) {
            if (StringUtils.equals(msgType, type)) {
                msgHandlerMap.get(type).process(content);
                log.info("post process for: {} success, content: {}", type, content);
                return;
            }
        }
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
