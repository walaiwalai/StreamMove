package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 本地缓存
 *
 * @Author : caiwen
 * @Date: 2025/1/31
 */
@Component
@Slf4j
public class LocalCacheManager {
    /**
     * 用于存储每个 key 的过期时间（时间戳）
     */
    private static final Map<String, Long> keyExpiryMap = new HashMap<>();
    private static final Cache<String, Object> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(7, TimeUnit.DAYS)
            .build();

    /**
     * 设置缓存值
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    public void set(String key, Object value) {
        set(key, value, 1, TimeUnit.DAYS);
    }

    /**
     * 设置缓存值并设置过期时间
     *
     * @param key      缓存键
     * @param value    缓存值
     * @param timeout  过期时间
     * @param timeUnit 时间单位
     */
    public void set(String key, Object value, long timeout, TimeUnit timeUnit) {
        cache.put(key, value);
        long seconds = timeUnit.toSeconds(timeout);
        keyExpiryMap.put(key, System.currentTimeMillis() + seconds * 1000);
    }

    /**
     * 获取缓存值
     *
     * @param key 缓存键
     * @return 缓存值
     */
    public String get(String key) {
        return get(key, new TypeReference<String>() {
        });
    }

    /**
     * 获取缓存值
     *
     * @param key           缓存键
     * @param typeReference 类型引用
     * @param <T>           泛型类型
     * @return 缓存值
     */
    public <T> T get(String key, TypeReference<T> typeReference) {
        Object value = cache.getIfPresent(key);
        T t = value != null ? JSON.parseObject(JSON.toJSONString(value), typeReference) : null;

        if (t != null) {
            Long expiryTime = keyExpiryMap.get(key);
            if (expiryTime != null && System.currentTimeMillis() > expiryTime) {
                cache.invalidate(key);
                keyExpiryMap.remove(key);
                return null;
            }
        }
        return t;
    }

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    public void delete(String key) {
        cache.invalidate(key);
    }

    /**
     * 判断缓存是否存在
     *
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean hasKey(String key) {
        return cache.getIfPresent(key) != null;
    }


    public void clearAll() {
        cache.invalidateAll();
        keyExpiryMap.clear();
    }
}