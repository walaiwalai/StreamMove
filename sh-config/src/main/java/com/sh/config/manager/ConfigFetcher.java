package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static volatile Map<String, StreamerConfig> name2StreamerMap;
    private static volatile InitConfig initConfig;

    /**
     * 根据名称获取直播人的相关信息
     *
     * @param name
     * @return
     */
    public static StreamerConfig getStreamerInfoByName(String name) {
        if (name2StreamerMap == null) {
            synchronized (ConfigFetcher.class) {
                if (name2StreamerMap == null) {
                    loadStreamConfig();
                }
            }
        }
        return name2StreamerMap.get(name);
    }

    /**
     * 获取streamer的列表
     *
     * @return
     */
    public static List<StreamerConfig> getStreamerInfoList() {
        if (name2StreamerMap == null) {
            synchronized (ConfigFetcher.class) {
                if (name2StreamerMap == null) {
                    loadStreamConfig();
                }
            }
        }
        return Lists.newArrayList(name2StreamerMap.values());
    }

    public static InitConfig getInitConfig() {
        if (initConfig == null) {
            synchronized (ConfigFetcher.class) {
                if (initConfig == null) {
                    loadInitConfig();
                }
            }
        }
        return initConfig;
    }

    public static void refresh() {
        loadStreamConfig();
        log.info("refresh config success");
    }

    public static void refreshStreamer(StreamerConfig updated) {
        String name = updated.getName();
        StreamerConfig existed = getStreamerInfoByName(name);
        if (updated.getLastRecordTime() != null) {
            existed.setLastRecordTime(updated.getLastRecordTime());
        }

        name2StreamerMap.put(name, existed);

        // 写入streamer.json
        try {
            File file = new File("/home/admin/stream", "streamer.json");
            IOUtils.write(JSON.toJSONString(getStreamerInfoList()), new FileOutputStream(file), "utf-8");
        } catch (IOException e) {
            log.error("update streamer.json fail", e);
        }
    }


    private static void loadInitConfig() {
        String configStr;
        try {
            if (EnvUtil.isProd()) {
                configStr = IOUtils.toString(Files.newInputStream(new File("/home/admin/stream/init.json").toPath()), "utf-8");
            } else {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                configStr = IOUtils.toString(classLoader.getResourceAsStream("config/init.json"), "utf-8");
            }
            initConfig = JSONObject.parseObject(configStr, InitConfig.class);
        } catch (Exception e) {
            log.error("error load init.json, please check it!", e);
        }
    }

    private static void loadStreamConfig() {
        String configStr;
        try {
            if (EnvUtil.isProd()) {
                configStr = IOUtils.toString(Files.newInputStream(new File("/home/admin/stream/streamer.json").toPath()), "utf-8");
            } else {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                configStr = IOUtils.toString(classLoader.getResourceAsStream("config/streamer.json"), "utf-8");
            }
            name2StreamerMap = JSONObject.parseArray(configStr).toJavaList(StreamerConfig.class).stream()
                    .peek(ConfigFetcher::fillDefaultValueForStreamerInfo)
                    .collect(Collectors.toMap(StreamerConfig::getName, Function.identity(), (a, b) -> b));
            log.info("load {} streamers success!", name2StreamerMap.keySet().size());
        } catch (Exception e) {
            log.error("error load streamer.json, please check it!", e);
        }
    }

    private static void fillDefaultValueForStreamerInfo(StreamerConfig streamerConfig) {
        streamerConfig.setDesc(Optional.ofNullable(streamerConfig.getDesc()).orElse("视频投稿"));
        streamerConfig.setSource(Optional.ofNullable(streamerConfig.getSource())
                .orElse(genDefaultDesc(streamerConfig.getName(), streamerConfig.getRoomUrl())));
        streamerConfig.setDynamic(Optional.ofNullable(streamerConfig.getDynamic())
                .orElse(genDefaultDesc(streamerConfig.getName(), streamerConfig.getRoomUrl())));
    }

    private static String genDefaultDesc(String name, String roomUrl) {
        return name + " 直播间：" + roomUrl;
    }
}
