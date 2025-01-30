package com.sh.config.manager;

import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.model.config.InitConfig;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.repo.StreamerRepoService;
import com.sh.config.utils.FileStoreUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
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
    @Resource
    private StreamerRepoService streamerRepoService;
    @Value("${system.env.flag}")
    private String systemEnvFlag;

    private static String initConfigPath;

    private static volatile Map<String, StreamerConfig> name2StreamerMap;
    private static volatile InitConfig initConfig;

    @PostConstruct
    private void init() {
        initConfigPath = environment.getProperty("init.config.path");

        initConfig = loadInitConfig();
        log.info("load init config success, path: {}", initConfigPath);

        name2StreamerMap = loadStreamConfig(systemEnvFlag);
        log.info("load {} streamers success", name2StreamerMap.keySet().size());
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

    public void refresh() {
        name2StreamerMap = loadStreamConfig(systemEnvFlag);
        log.info("refresh config success");
    }

    public void refreshStreamer( String name ) {
        StreamerConfig cur = streamerRepoService.getByName(name);
        name2StreamerMap.put(name, cur);
    }


    private static InitConfig loadInitConfig() {
        return FileStoreUtil.loadFromFile(new File(initConfigPath), new TypeReference<InitConfig>() {
        });
    }

    private Map<String, StreamerConfig> loadStreamConfig( String env ) {
        if (StringUtils.isBlank(env)) {
            return Maps.newHashMap();
        }
        List<StreamerConfig> streamerConfigs = streamerRepoService.getByEnv(env);
        return streamerConfigs.stream()
                .peek(ConfigFetcher::fillDefaultValueForStreamerInfo)
                .collect(Collectors.toMap(StreamerConfig::getName, Function.identity(), ( a, b ) -> b));
    }

    private static void fillDefaultValueForStreamerInfo(StreamerConfig streamerConfig) {
        streamerConfig.setDesc(Optional.ofNullable(streamerConfig.getDesc()).orElse("视频投稿"));
        streamerConfig.setSource(Optional.ofNullable(streamerConfig.getSource())
                .orElse(streamerConfig.getName() + " 直播间：" + streamerConfig.getRoomUrl()));
    }
}
