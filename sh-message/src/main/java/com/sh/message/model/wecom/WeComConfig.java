package com.sh.message.model.wecom;

import lombok.Data;

@Data
public class WeComConfig {
    /**
     * 微应用相关token
     */
    private String agentId;
    private String secret;

    /**
     * 消息事件token
     */
    private String eventToken;

    private String encodingAesKey;

    private String corpId;
}
