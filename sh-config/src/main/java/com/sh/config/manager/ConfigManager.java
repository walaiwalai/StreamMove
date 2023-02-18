package com.sh.config.manager;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.constant.StreamHelperPathConfig;
import com.sh.config.model.config.StreamHelperConfig;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.config.UploadPersonInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author caiWen
 * @date 2023/1/23 10:49
 */
@Slf4j
@Component
public class ConfigManager {
    private static Map<String, StreamerInfo> name2StreamerMap = Maps.newLinkedHashMap();
    private static StreamHelperConfig streamHelperConfig;
    private static UploadPersonInfo uploadPersonInfo;

    @PostConstruct
    public void init() {
        loadConfigFromFile();
        log.info("load config success");
    }

    /**
     * 根据名称获取直播人的相关信息
     * @param name
     * @return
     */
    public StreamerInfo getStreamerInfoByName(String name) {
        return name2StreamerMap.get(name);
    }

    /**
     * 获取streamer的列表
     * @return
     */
    public List<StreamerInfo> getStreamerInfoList() {
        return Lists.newArrayList(name2StreamerMap.values());
    }

    /**
     * 获取拉流的相关配置
     * @return
     */
    public StreamHelperConfig getStreamHelperConfig() {
        return streamHelperConfig;
    }

    /**
     * 获取上传视频的用户信息
     * @return
     */
    public UploadPersonInfo getUploadPersonInfo() {
        return uploadPersonInfo;
    }

//    public void syncUploadPersonInfoToConfig(UploadPersonInfo updateUserInfo) {
//        // 最新值进行覆盖
//        UploadPersonInfo olderPersonInfo = getConfig().getPersonInfo();
//        streamerHelperInfoConfig.setPersonInfo(UploadPersonInfo.builder()
//                .accessToken(Optional.ofNullable(updateUserInfo.getAccessToken()).orElse(olderPersonInfo.getAccessToken()))
//                .mid(Optional.ofNullable(updateUserInfo.getMid()).orElse(olderPersonInfo.getMid()))
//                .expiresIn(Optional.ofNullable(updateUserInfo.getExpiresIn()).orElse(olderPersonInfo.getExpiresIn()))
//                .nickname(Optional.ofNullable(updateUserInfo.getNickname()).orElse(olderPersonInfo.getNickname()))
//                .refreshToken(Optional.ofNullable(updateUserInfo.getRefreshToken()).orElse(olderPersonInfo.getRefreshToken()))
//                .tokenSignDate(Optional.ofNullable(updateUserInfo.getTokenSignDate()).orElse(olderPersonInfo.getTokenSignDate()))
//                .build());
//        writeConfigToFile();
//    }


    private void loadConfigFromFile() {
        File file = new File(StreamHelperPathConfig.APP_PATH, "info.json");
        try {
            String configStr = IOUtils.toString(new FileInputStream(file), "utf-8");
            JSONObject configObj = JSONObject.parseObject(configStr);
            streamHelperConfig = configObj.getJSONObject("streamerHelper").toJavaObject(StreamHelperConfig.class);
            uploadPersonInfo = configObj.getJSONObject("uploadPersonInfo").toJavaObject(UploadPersonInfo.class);
            name2StreamerMap = configObj.getJSONArray("streamerInfos").toJavaList(StreamerInfo.class).stream()
                    .peek(this::fillDefaultValueForStreamerInfo)
                    .collect(Collectors.toMap(StreamerInfo::getName, Function.identity(), (a, b) -> b));
        } catch (Exception e) {
            log.error("error load info.json, please check it!", e);
        }
    }

    private void fillDefaultValueForStreamerInfo(StreamerInfo streamerInfo) {
        streamerInfo.setDesc(Optional.ofNullable(streamerInfo.getDesc()).orElse("视频投稿"));
        streamerInfo.setSource(Optional.ofNullable(streamerInfo.getSource())
                .orElse(genDefaultDesc(streamerInfo.getName(), streamerInfo.getRoomUrl())));
        streamerInfo.setDynamic(Optional.ofNullable(streamerInfo.getDynamic())
                .orElse(genDefaultDesc(streamerInfo.getName(), streamerInfo.getRoomUrl())));
        streamerInfo.setUploadLocalFile(
                Optional.ofNullable(streamerInfo.getUploadLocalFile()).orElse(true));
    }

    private String genDefaultDesc(String name, String roomUrl) {
        return name + " 直播间：" + roomUrl;
    }

//    private void writeConfigToFile() {
//        File file = new File(StreamHelperConstant.APP_PATH, "info.json");
//        try {
//            IOUtils.write(JSON.toJSONString(getConfig()), new FileOutputStream(file), "utf-8");
//        } catch (IOException e) {
//            log.error("fail write newest config into info.json");
//        }
//    }
}
