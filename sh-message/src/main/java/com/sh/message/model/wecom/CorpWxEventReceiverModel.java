package com.sh.message.model.wecom;

import lombok.Data;

/**
 * @Author caiwen
 * @Date 2023 07 29 15 52
 **/
@Data
public class CorpWxEventReceiverModel {
    /**
     * 消息签名体
     */
    private String msg_signature;

    /**
     * 时间戳
     */
    private String timestamp;

    /**
     * 随机字符串
     */
    private String nonce;

    /**
     * 推送过来的加密字符串
     */
    private String echostr;
}
