package com.sh.config.manager;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
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
public class ConfigFetcher {
    private static Map<String, StreamerInfo> name2StreamerMap = Maps.newLinkedHashMap();
    private static InitConfig initConfig;

    static {
        loadStreamConfig();
        loadInitConfig();
        log.info("load config success");
    }

    /**
     * 根据名称获取直播人的相关信息
     * @param name
     * @return
     */
    public static StreamerInfo getStreamerInfoByName(String name) {
        return name2StreamerMap.get(name);
    }

    /**
     * 获取streamer的列表
     * @return
     */
    public static List<StreamerInfo> getStreamerInfoList() {
        return Lists.newArrayList(name2StreamerMap.values());
    }

    public static InitConfig getInitConfig() {
        return initConfig;
    }

    public static void refresh() {
        loadStreamConfig();
        loadInitConfig();
        log.info("refresh config success");
    }


    private static void loadInitConfig() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            // String configStr = IOUtils.toString(classLoader.getResourceAsStream("config/init.json"), "utf-8");
            String configStr = IOUtils.toString(Files.newInputStream(new File("/home/admin/stream/init.json").toPath()), "utf-8");
            initConfig = JSONObject.parseObject(configStr, InitConfig.class);
        } catch (Exception e) {
            log.error("error load init.json, please check it!", e);
        }
    }

    private static void loadStreamConfig() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        try {
//            String configStr = IOUtils.toString(classLoader.getResourceAsStream("config/streamer.json"), "utf-8");
            String configStr = IOUtils.toString(Files.newInputStream(new File("/home/admin/stream/streamer.json").toPath()), "utf-8");
            name2StreamerMap = JSONObject.parseArray(configStr).toJavaList(StreamerInfo.class).stream()
                    .peek(ConfigFetcher::fillDefaultValueForStreamerInfo)
                    .collect(Collectors.toMap(StreamerInfo::getName, Function.identity(), (a, b) -> b));
        } catch (Exception e) {
            log.error("error load streamer.json, please check it!", e);
        }
    }

    private static void fillDefaultValueForStreamerInfo(StreamerInfo streamerInfo) {
        streamerInfo.setDesc(Optional.ofNullable(streamerInfo.getDesc()).orElse("视频投稿"));
        streamerInfo.setSource(Optional.ofNullable(streamerInfo.getSource())
                .orElse(genDefaultDesc(streamerInfo.getName(), streamerInfo.getRoomUrl())));
        streamerInfo.setDynamic(Optional.ofNullable(streamerInfo.getDynamic())
                .orElse(genDefaultDesc(streamerInfo.getName(), streamerInfo.getRoomUrl())));
    }

    private static String genDefaultDesc(String name, String roomUrl) {
        return name + " 直播间：" + roomUrl;
    }
}
