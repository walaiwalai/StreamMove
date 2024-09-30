package com.sh.engine.listener;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Sets;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.UploadPlatformEnum;
import com.sh.engine.model.upload.Uploader;
import com.sh.engine.upload.UploaderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class UploaderInitListener implements ApplicationListener<ContextRefreshedEvent> {

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        Set<String> platforms = ConfigFetcher.getStreamerInfoList().stream()
                .map(StreamerConfig::getUploadPlatforms)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        initUploader(platforms);
        log.info("init uploader finish, uploader: {}", JSON.toJSONString(platforms));
    }

    private void initUploader(Set<String> platforms) {
        List<UploadPlatformEnum> platformEnums = platforms.stream().map(UploadPlatformEnum::of)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        for (UploadPlatformEnum platformEnum : platformEnums) {
            try {
                Uploader uploader = UploaderFactory.getUploader(platformEnum.getType());
                uploader.init();
                uploader.setUp();
            } catch (Exception e) {
                log.error("init uploader failed, platform: {}", platformEnum.getType());
            }
        }
    }

    public static void main(String[] args) {
        UploaderInitListener uploaderInitListener = new UploaderInitListener();
        uploaderInitListener.initUploader(Sets.newHashSet("DOU_YIN"));
    }
}
