package com.sh.config.repo;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.config.mapper.StreamerMapper;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.dao.StreamerDO;
import com.sh.config.model.dao.StreamerExtraDO;
import com.sh.config.model.dao.StreamerExtraDO.BiliUploadInfoDO;
import com.sh.config.model.dao.StreamerExtraDO.DouyinUploadInfoDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
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

    @Override
    public void updateByName(String name, StreamerConfig updated) {
        StreamerDO streamerDO = convertToStreamerDO(updated);
        streamerMapper.updateByName(name, streamerDO);
    }

    public void updateLastRecordTime(String name, Date lastRecordTime) {
        streamerMapper.updateLastRecordTime(name, lastRecordTime);
    }


    @Override
    public void deleteByName(String name) {
        streamerMapper.deleteByName(name);
    }

    @Override
    public void insert(StreamerConfig streamer, String env) {
        StreamerDO streamerDO = convertToStreamerDO(streamer);
        streamerDO.setEnv(env);
        streamerMapper.insert(streamerDO);
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
                .lastVodCnt(streamerDO.getLastVodCnt() != null ? streamerDO.getLastVodCnt() : 1)
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
                .segMergeCnt(streamerDO.getSegMergeCnt())
                .maxMergeSize(streamerDO.getMaxMergeSize())
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
            }

            DouyinUploadInfoDO douyinUploadInfo = streamerExtraDO.getDouyinUploadInfo();
            if (douyinUploadInfo != null) {
                config.setLocation(douyinUploadInfo.getLocation());
            }
            config.setCertainVodUrls(streamerExtraDO.getCertainVodUrls());
        }

        return config;
    }

    private StreamerDO convertToStreamerDO(StreamerConfig streamerConfig) {
        if (streamerConfig == null) {
            return null;
        }
        StreamerExtraDO extra = StreamerExtraDO.builder()
                .biliUploadInfo(BiliUploadInfoDO.builder()
                        .source(streamerConfig.getSource())
                        .tid(streamerConfig.getTid())
                        .cover(streamerConfig.getCover())
                        .openingAnimations(streamerConfig.getBiliOpeningAnimations())
                        .build())
                .douyinUploadInfo(DouyinUploadInfoDO.builder()
                        .location(streamerConfig.getLocation())
                        .build())
                .certainVodUrls(streamerConfig.getCertainVodUrls())
                .build();

        return StreamerDO.builder()
                .name(streamerConfig.getName())
                .roomUrl(streamerConfig.getRoomUrl())
                .recordType(streamerConfig.isRecordWhenOnline() ? "living" : "vod")
                .lastRecordTime(streamerConfig.getLastRecordTime())
                .expireTime(streamerConfig.getExpireTime())
                .lastVodCnt(streamerConfig.getLastVodCnt())
                .segMergeCnt(streamerConfig.getSegMergeCnt())
                .maxMergeSize(streamerConfig.getMaxMergeSize())
                .templateTitle(streamerConfig.getTemplateTitle())
                .coverPath(streamerConfig.getCoverFilePath())
                .desc(streamerConfig.getDesc())
                .uploadPlatforms(CollectionUtils.isNotEmpty(streamerConfig.getUploadPlatforms()) ?
                        StringUtils.join(streamerConfig.getUploadPlatforms(), ",") : null)
                .processPlugins(CollectionUtils.isNotEmpty(streamerConfig.getVideoPlugins()) ?
                        StringUtils.join(streamerConfig.getVideoPlugins(), ",") : null)
                .tags(CollectionUtils.isNotEmpty(streamerConfig.getTags()) ? StringUtils.join(streamerConfig.getTags(), ",") : null)
                .extra(JSON.toJSONString(extra))
                .build();
    }

    private StreamerExtraDO parseExtra(String extra) {
        if (StringUtils.isBlank(extra)) {
            return null;
        }
        return JSON.parseObject(extra, StreamerExtraDO.class);
    }
}
