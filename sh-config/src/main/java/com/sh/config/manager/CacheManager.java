package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.sh.config.utils.IPUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * @Author caiwen
 * @Date 2024 09 29 09 57
 **/
@Component
@Slf4j
public class CacheManager {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    private HashOperations<String, String, String> hashOps;

    @PostConstruct
    private void init() {
        hashOps = redisTemplate.opsForHash();
    }

    /**
     * 设置缓存值
     *
     * @param key   缓存键
     * @param value 缓存值
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, obj2String(value));
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
        redisTemplate.opsForValue().set(key, obj2String(value), timeout, timeUnit);
    }

    /**
     * 设置哈希中的键值对
     *
     * @param key   主键
     * @param tag   哈希字段
     * @param value 值
     */
    public void setHash(String key, String tag, Object value) {
        hashOps.put(key, tag, obj2String(value));
    }

    /**
     * 设置哈希中的键值对
     *
     * @param key   主键
     * @param tag   哈希字段
     * @param value 值
     */
    public void setHash(String key, String tag, Object value, long timeout, TimeUnit timeUnit) {
        hashOps.put(key, tag, obj2String(value));
        redisTemplate.expire(key, timeout, timeUnit);
    }

    /**
     * 获取哈希中的值
     *
     * @param key           主键
     * @param field         哈希字段
     * @param typeReference 目标类
     * @return 转换后的对象
     */
    public <T> T getHash(String key, String field, TypeReference<T> typeReference) {
        try {
            return string2Obj(hashOps.get(key, field), typeReference);
        } catch (Exception e) {
            log.error("get hash error, key: {}, field: {}", key, field, e);
            return null;
        }
    }

    /**
     * 删除哈希中的字段
     *
     * @param key 主键
     * @param tag 哈希字段
     */
    public void deleteHashTag(String key, String tag) {
        hashOps.delete(key, tag);
    }

    /**
     * 获取哈希中的字段数量
     *
     * @param key 主键
     * @return 字段数量
     */
    public long getHashSize(String key) {
        return hashOps.size(key);
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
        try {
            return string2Obj((String) redisTemplate.opsForValue().get(key), typeReference);
        } catch (Exception e) {
            log.error("get value error，key: {}", key, e);
            return null;
        }
    }

    /**
     * 获取缓存值
     *
     * @param key           缓存键
     * @return 缓存值
     */
    public String get(String key) {
        return get(key, new TypeReference<String>() {});
    }

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 判断缓存是否存在
     *
     * @param key 缓存键
     * @return 是否存在
     */
    public boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * 设置缓存过期时间
     *
     * @param key      缓存键
     * @param timeout  过期时间
     * @param timeUnit 时间单位
     * @return 是否设置成功
     */
    public boolean expire(String key, long timeout, TimeUnit timeUnit) {
        return redisTemplate.expire(key, timeout, timeUnit);
    }

    /**
     * 对象转String
     *
     * @param src
     * @param <T>
     * @return
     */
    private static <T> String obj2String(T src) {
        return src instanceof String ? (String) src : JSON.toJSONString(src);
    }

    private static <T> T string2Obj(String src, TypeReference<T> typeReference) {
        return (T) (typeReference.getType().equals(String.class) ? src : JSON.parseObject(src, typeReference));
    }
}
