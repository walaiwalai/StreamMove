package com.sh.message.config;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.InitConfig;
import com.sh.message.model.wecom.WeComConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author caiwen
 * @Date 2024 09 29 11 17
 **/
@Configuration
public class MessageConfiguration {
    @Value("${wecom.corp.id}")
    private String corpId;
    @Value("${wecom.agent.id}")
    private String agentId;
    @Value("${wecom.agent.secret}")
    private String secret;
    @Value("${wecom.event.token}")
    private String eventToken;
    @Value("${wecom.event.encoding-aes-key}")
    private String encodingAesKey;

    @Bean
    public WeComConfig weComConfig() {
        WeComConfig weComConfig = new WeComConfig();
        weComConfig.setAgentId(agentId);
        weComConfig.setSecret(secret);
        weComConfig.setEventToken(eventToken);
        weComConfig.setEncodingAesKey(encodingAesKey);
        weComConfig.setCorpId(corpId);
        return weComConfig;
    }
}