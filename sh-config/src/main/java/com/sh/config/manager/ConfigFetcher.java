package com.sh.config.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.FileStoreUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
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
@Component
public class ConfigFetcher {
    @Resource
    private Environment environment;

    private static String initConfigPath;
    private static String streamerConfigPath;


    private static volatile Map<String, StreamerConfig> name2StreamerMap;
    private static volatile InitConfig initConfig;

    @PostConstruct
    private void init() {
        initConfigPath = environment.getProperty("init.config.path");
        streamerConfigPath = environment.getProperty("streamer.config.path");

        initConfig = loadInitConfig();
        log.info("load init config success, path: {}", initConfigPath);

        name2StreamerMap = loadStreamConfig();
        log.info("load {} streamers success, path: {}", name2StreamerMap.keySet().size(), streamerConfigPath);
    }

    /**
     * 根据名称获取直播人的相关信息
     *
     * @param name
     * @return
     */
    public static StreamerConfig getStreamerInfoByName(String name) {
        return name2StreamerMap.get(name);
    }

    /**
     * 获取streamer的列表
     *
     * @return
     */
    public static List<StreamerConfig> getStreamerInfoList() {
        return Lists.newArrayList(name2StreamerMap.values());
    }

    public static InitConfig getInitConfig() {
        if (initConfig != null) {
            return initConfig;
        }
        return loadInitConfig();
    }

    public static void refresh() {
        name2StreamerMap = loadStreamConfig();
        log.info("refresh config success");
    }

    public static void refreshStreamer(String name, StreamerConfig updated) {
        StreamerConfig existed = getStreamerInfoByName(name);
        if (updated.getLastRecordTime() != null) {
            existed.setLastRecordTime(updated.getLastRecordTime());
        }

        name2StreamerMap.put(name, existed);

        // 写入streamer.json
        try {
            IOUtils.write(JSON.toJSONString(getStreamerInfoList()), Files.newOutputStream(new File(streamerConfigPath).toPath()), "utf-8");
        } catch (IOException e) {
            log.error("update streamer.json fail", e);
        }
    }


    private static InitConfig loadInitConfig() {
        return FileStoreUtil.loadFromFile(new File(initConfigPath), new TypeReference<InitConfig>() {
        });
    }

    private static Map<String, StreamerConfig> loadStreamConfig() {
        List<StreamerConfig> streamerConfigs = FileStoreUtil.loadFromFile(new File(streamerConfigPath), new TypeReference<List<StreamerConfig>>() {
        });
        return streamerConfigs.stream()
                .peek(ConfigFetcher::fillDefaultValueForStreamerInfo)
                .collect(Collectors.toMap(StreamerConfig::getName, Function.identity(), (a, b) -> b));
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
