package com.sh.engine.manager;

import com.alibaba.fastjson.TypeReference;
import com.sh.config.manager.CacheManager;
import com.sh.engine.model.bili.RecordSegmentInfo;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * @Author : caiwen
 * @Date: 2025/8/23
 */
@Component
public class StreamCacheManager {
    @Resource
    private CacheManager cacheManager;

    public void cacheSegmentsInfo(String recordPath, RecordSegmentInfo recordSegmentInfo) {
        cacheManager.set("recordSeg#" + recordPath, recordSegmentInfo, 2, TimeUnit.DAYS);
    }

    public RecordSegmentInfo getSegmentsInfo(String recordPath) {
        return cacheManager.get("recordSeg#" + recordPath, new TypeReference<RecordSegmentInfo>() {});
    }
}
