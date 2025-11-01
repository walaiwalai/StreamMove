package com.sh.config.repo;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.config.mapper.StreamerMapper;
import com.sh.config.mapper.StreamerWorkMapper;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.dao.StreamerDO;
import com.sh.config.model.dao.StreamerExtraDO;
import com.sh.config.model.dao.StreamerExtraDO.BiliUploadInfoDO;
import com.sh.config.model.dao.StreamerExtraDO.DouyinUploadInfoDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StreamerRepoServiceImpl implements StreamerRepoService {

    @Resource
    private StreamerMapper streamerMapper;
    @Resource
    private StreamerWorkMapper streamerWorkMapper;

//    @PostConstruct
//    public void init() {
//        List<StreamerConfig> streamerConfigs = getByEnv("shy");
//        for (StreamerConfig streamerConfig : streamerConfigs) {
//            List<String> videoPlugins = streamerConfig.getVideoPlugins();
//            if (!videoPlugins.contains("LOL_HL_VOD_CUT")) {
//                continue;
//            }
//
//            String name = streamerConfig.getName();
//            streamerConfig.setBiliOpeningAnimations(
//                    Lists.newArrayList()
////                    Lists.newArrayList("/home/admin/stream/thumbnail/raybet3.mp4")
//
//            );
//            updateByName(name, streamerConfig);
//            log.info("{} updated success", name);
//        }
//    }

    @Override
    public StreamerConfig getByName(String name) {
        StreamerDO streamerDO = streamerMapper.selectByName(name);
        if (streamerDO == null) {
            return null;
        }
        return convertToStreamerConfig(streamerDO);
    }

    @Override
    public List<StreamerConfig> getByEnv(String env) {
        List<StreamerDO> streamerDOList = streamerMapper.batchSelectByEnv(env);
        return streamerDOList.stream()
                .map(this::convertToStreamerConfig)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void updateLastRecordTime(String name, Date lastRecordTime) {
        streamerMapper.updateLastRecordTime(name, lastRecordTime);
    }

    @Override
    public void updateTrafficGB(String name, float trafficGBCost) {
        StreamerConfig config = getByName(name);
        float curTrafficGB = config.getCurTrafficGB() + trafficGBCost;
        streamerMapper.updateTrafficGB(name, curTrafficGB);
    }

    private StreamerConfig convertToStreamerConfig(StreamerDO streamerDO) {
        if (streamerDO == null) {
            return null;
        }
        StreamerConfig config = StreamerConfig.builder()
                .name(streamerDO.getName())
                .roomUrl(streamerDO.getRoomUrl())
                .recordWhenOnline("living".equals(streamerDO.getRecordType()))
                .lastRecordTime(streamerDO.getLastRecordTime())
                .expireTime(streamerDO.getExpireTime())
                .videoPlugins(StringUtils.isNotBlank(streamerDO.getProcessPlugins()) ?
                        Arrays.stream(StringUtils.split(streamerDO.getProcessPlugins(), ","))
                                .map(String::trim)
                                .collect(Collectors.toList())
                        : Lists.newArrayList())
                .uploadPlatforms(StringUtils.isNotBlank(streamerDO.getUploadPlatforms()) ?
                        Arrays.stream(StringUtils.split(streamerDO.getUploadPlatforms(), ","))
                                .map(String::trim)
                                .collect(Collectors.toList())
                        : Lists.newArrayList())
                .templateTitle(streamerDO.getTemplateTitle())
                .desc(streamerDO.getDesc())
                .tags(StringUtils.isNotBlank(streamerDO.getTags()) ?
                        Arrays.stream(StringUtils.split(streamerDO.getTags(), ","))
                                .map(String::trim)
                                .collect(Collectors.toList())
                        : Lists.newArrayList())
                .recordMode(streamerDO.getRecordMode())
                .curTrafficGB(streamerDO.getCurTrafficGB())
                .maxTrafficGB(streamerDO.getMaxTrafficGB())
                .coverFilePath(streamerDO.getCoverPath())
                .build();

        StreamerExtraDO streamerExtraDO = parseExtra(streamerDO.getExtra());
        if (streamerExtraDO != null) {
            BiliUploadInfoDO biliUploadInfo = streamerExtraDO.getBiliUploadInfo();
            if (biliUploadInfo != null) {
                config.setSource(biliUploadInfo.getSource());
                config.setTid(biliUploadInfo.getTid());
                config.setCover(biliUploadInfo.getCover());
                config.setBiliOpeningAnimations(biliUploadInfo.getOpeningAnimations());
                config.setCertainBiliCookies(biliUploadInfo.getCertainBiliCookies());
            }

            DouyinUploadInfoDO douyinUploadInfo = streamerExtraDO.getDouyinUploadInfo();
            if (douyinUploadInfo != null) {
                config.setLocation(douyinUploadInfo.getLocation());
            }
            config.setCertainVodUrls(streamerExtraDO.getCertainVodUrls());
            config.setOnlyAudio(streamerExtraDO.isOnlyAudio());
            config.setRecordDamaku(streamerExtraDO.isRecordDamaku());
            config.setRecordQuality(streamerExtraDO.getRecordQuality());
            config.setOnlinePushCheck(streamerExtraDO.isOnlinePushCheck());
        }

        return config;
    }

    private StreamerExtraDO parseExtra(String extra) {
        if (StringUtils.isBlank(extra)) {
            return null;
        }
        return JSON.parseObject(extra, StreamerExtraDO.class);
    }
}
