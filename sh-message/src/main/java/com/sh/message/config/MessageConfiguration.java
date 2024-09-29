package com.sh.message.config;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.InitConfig;
import com.sh.message.model.wecom.WeComConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author caiwen
 * @Date 2024 09 29 11 17
 **/
@Configuration
public class MessageConfiguration {
    @Bean
    public WeComConfig weComConfig() {
        InitConfig initConfig = ConfigFetcher.getInitConfig();
        WeComConfig weComConfig = new WeComConfig();
        weComConfig.setAgentId(initConfig.getWeComAgentId());
        weComConfig.setSecret(initConfig.getWeComSecret());
        weComConfig.setEventToken(initConfig.getWeComEventToken());
        weComConfig.setEncodingAesKey(initConfig.getWeComEncodingAesKey());
        weComConfig.setCorpId(initConfig.getWeComCorpId());
        return weComConfig;
    }
}
