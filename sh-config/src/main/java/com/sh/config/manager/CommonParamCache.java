package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.config.mapper.CommonParamMapper;
import com.sh.config.model.config.CommonParamKey;
import com.sh.config.model.dao.CommonParamDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将当前环境的通用参数加载到独立的 Redis Hash。
 */
@Slf4j
@Component
public class CommonParamCache {
    private static final String CACHE_KEY_PREFIX = "streamCommonParam:";

    @Resource
    private CommonParamMapper commonParamMapper;
    @Resource
    private CacheManager cacheManager;
    @Value("${system.env.flag}")
    private String currentEnv;

    /**
     * 启动及手动刷新时，将当前环境的数据库参数替换到对应的 Redis Hash。
     */
    @PostConstruct
    public void refresh() {
        List<CommonParamDO> params = commonParamMapper.selectByEnv(currentEnv);
        Map<String, String> values = new HashMap<>();
        for (CommonParamDO param : params) {
            values.put(param.getKey(), param.getValue());
        }

        cacheManager.replaceHash(CACHE_KEY_PREFIX + currentEnv, values);
        log.info("Common parameters refreshed, env: {}, count: {}", currentEnv, values.size());
    }

    /**
     * 读取当前环境的参数。
     */
    public String get(CommonParamKey key) {
        return cacheManager.getHashValue(CACHE_KEY_PREFIX + currentEnv, key.getKey());
    }

    /**
     * 读取整数参数；未配置时返回默认值，非法数字直接报错。
     */
    public int getInt(CommonParamKey key, int defaultValue) {
        String value = get(key);
        return StringUtils.isBlank(value) ? defaultValue : Integer.parseInt(value.trim());
    }

    /**
     * 读取 JSON 字符串数组；未配置时返回空列表。
     */
    public List<String> getStringList(CommonParamKey key) {
        return getStringList(key, Collections.emptyList());
    }

    /**
     * 读取 JSON 字符串数组；未配置时返回空列表。
     */
    public List<String> getStringList(CommonParamKey key, List<String> defaultList) {
        String value = get(key);
        if (StringUtils.isBlank(value)) {
            return Collections.emptyList();
        }
        List<String> items = JSON.parseArray(value, String.class);
        return items == null ? defaultList : items;
    }
}
