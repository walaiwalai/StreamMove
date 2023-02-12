package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.sh.config.constant.StreamHelperConstant;
import com.sh.config.model.config.ShGlobalConfig;
import com.sh.config.model.config.UploadPersonInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/1/23 10:49
 */
@Slf4j
@Component
public class ConfigManager {
    private ShGlobalConfig streamerHelperInfoConfig;

    @PostConstruct
    public void init() {
        loadConfigFromFile();
        log.info("reload config success");
    }

    public ShGlobalConfig getConfig() {
        return this.streamerHelperInfoConfig;
    }

    public void syncUploadPersonInfoToConfig(UploadPersonInfo updateUserInfo) {
        // 最新值进行覆盖
        UploadPersonInfo olderPersonInfo = getConfig().getPersonInfo();
        streamerHelperInfoConfig.setPersonInfo(UploadPersonInfo.builder()
                .accessToken(Optional.ofNullable(updateUserInfo.getAccessToken()).orElse(olderPersonInfo.getAccessToken()))
                .mid(Optional.ofNullable(updateUserInfo.getMid()).orElse(olderPersonInfo.getMid()))
                .expiresIn(Optional.ofNullable(updateUserInfo.getExpiresIn()).orElse(olderPersonInfo.getExpiresIn()))
                .nickname(Optional.ofNullable(updateUserInfo.getNickname()).orElse(olderPersonInfo.getNickname()))
                .refreshToken(Optional.ofNullable(updateUserInfo.getRefreshToken()).orElse(olderPersonInfo.getRefreshToken()))
                .tokenSignDate(Optional.ofNullable(updateUserInfo.getTokenSignDate()).orElse(olderPersonInfo.getTokenSignDate()))
                .build());
        writeConfigToFile();
    }


    private void loadConfigFromFile() {
        File file = new File(StreamHelperConstant.APP_PATH, "info.json");
        try {
            String configStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            this.streamerHelperInfoConfig = JSON.parseObject(configStr, ShGlobalConfig.class);
        } catch (Exception e) {
            log.error("error load info.json, please check it!", e);
        }
    }

    private void writeConfigToFile() {
        File file = new File(StreamHelperConstant.APP_PATH, "info.json");
        try {
            IOUtils.write(JSON.toJSONString(getConfig()), new FileOutputStream(file), "utf-8");
        } catch (IOException e) {
            log.error("fail write newest config into info.json");
        }
    }
}
