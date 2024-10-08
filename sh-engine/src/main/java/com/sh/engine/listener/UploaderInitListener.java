package com.sh.engine.listener;

import com.alibaba.fastjson.JSON;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.processor.uploader.Uploader;
import com.sh.engine.processor.uploader.UploaderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class UploaderInitListener implements ApplicationListener<ApplicationReadyEvent>{

    @Override
    public void onApplicationEvent(ApplicationReadyEvent applicationReadyEvent) {
        initUploader();
    }

    private void initUploader() {
        Set<String> platforms = ConfigFetcher.getStreamerInfoList().stream()
                .map(StreamerConfig::getUploadPlatforms)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
        for (String platform : platforms) {
            try {
                Uploader uploader = UploaderFactory.getUploader(platform);

                uploader.setUp();
            } catch (Exception e) {
                log.error("init uploader failed, platform: {}", platform, e);
            }
        }
        log.info("init uploader success, uploaders: {}", JSON.toJSONString(platforms));
    }
}
