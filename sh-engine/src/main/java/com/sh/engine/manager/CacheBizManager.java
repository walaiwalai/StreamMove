package com.sh.engine.manager;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class CacheBizManager {
    @Resource
    private CacheManager cacheManager;

    public boolean isCertainVideoFinished(String streamerName, String videoId) {
        String key = "certain_keys_" + streamerName;
        String finishFlag = cacheManager.getHash(key, videoId, new TypeReference<String>() {
        });
        return StringUtils.isNotBlank(finishFlag);
    }

    public void finishCertainVideo(String streamerName, String videoId) {
        String key = "certain_keys_" + streamerName;
        cacheManager.setHash(key, videoId, "1", 2, TimeUnit.DAYS);
    }
}
