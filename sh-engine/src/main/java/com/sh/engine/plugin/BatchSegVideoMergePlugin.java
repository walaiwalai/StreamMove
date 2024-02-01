package com.sh.engine.plugin;

import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class BatchSegVideoMergePlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;

    @Override
    public String getPluginName() {
        return "BATCH_SEG_MERGE";
    }

    @Override
    public boolean process() {

    }
}
