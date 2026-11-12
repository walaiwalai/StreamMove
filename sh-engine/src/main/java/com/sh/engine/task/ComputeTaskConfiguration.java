package com.sh.engine.task;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.SerializationCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class ComputeTaskConfiguration {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient computeRedissonClient(RedisProperties redis) {
        Config config = new Config();
        config.setCodec(new SerializationCodec());
        config.setThreads(2);
        config.setNettyThreads(2);
        config.setLazyInitialization(true);
        SingleServerConfig server = config.useSingleServer()
                .setAddress((redis.isSsl() ? "rediss://" : "redis://") + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase())
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(4);
        if (StringUtils.hasText(redis.getPassword())) {
            server.setPassword(redis.getPassword());
        }
        if (redis.getTimeout() != null) {
            server.setTimeout(Math.toIntExact(redis.getTimeout().toMillis()));
        }
        return Redisson.create(config);
    }
}
