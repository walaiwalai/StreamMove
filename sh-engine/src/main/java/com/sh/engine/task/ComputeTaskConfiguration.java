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
                .setTimeout(10_000)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(16)
                // Avoid reconnect initialization waiting on a pool occupied by active subscriptions.
                .setSubscriptionConnectionMinimumIdleSize(0).setSubscriptionConnectionPoolSize(8);
        if (StringUtils.hasText(redis.getPassword())) {
            config.setPassword(redis.getPassword());
        }
        if (redis.getTimeout() != null) {
            server.setTimeout(Math.toIntExact(redis.getTimeout().toMillis()));
        }
        return Redisson.create(config);
    }
}
