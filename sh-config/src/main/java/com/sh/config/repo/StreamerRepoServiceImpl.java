package com.sh.config.repo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.sh.config.mapper.StreamerMapper;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.dao.StreamerDO;
import com.sh.config.model.dao.StreamerExtraDO;
import com.sh.config.model.dao.StreamerExtraDO.BiliUploadInfoDO;
import com.sh.config.model.dao.StreamerExtraDO.DouyinUploadInfoDO;
import com.sh.config.utils.FileStoreUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
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
//        List<StreamerConfig> streamerConfigs = FileStoreUtil.loadFromFile(new File("/Users/caiwen/Documents/streamer.json"), new TypeReference<List<StreamerConfig>>() {
//        });
//        Date date = new Date();
//        for (StreamerConfig config : streamerConfigs) {
//            config.setExpireTime(DateUtils.addYears(date, 50));
//            config.setLastRecordTime(date);
//            insert(config);
//            StreamerConfig saved = getByName(config.getName());
//            log.info("insert streamer: {} success, saved: {}", config.getName(), JSON.toJSONString(saved));
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
    public List<StreamerConfig> getByNames( List<String> names ) {
        List<StreamerDO> streamerDOList = streamerMapper.batchSelectByNames(names);
        return streamerDOList.stream()
                .map(this::convertToStreamerConfig)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public void updateByName(String name, StreamerConfig updated) {
        StreamerDO streamerDO = convertToStreamerDO(updated);
        streamerMapper.updateByName(streamerDO);
    }

    public void updateLastRecordTime( String name, Date lastRecordTime ) {
        streamerMapper.updateLastRecordTime(name, lastRecordTime);
    }


    @Override
    public void deleteByName(String name) {
        streamerMapper.deleteByName(name);
    }

    @Override
    public void insert(StreamerConfig streamer) {
        StreamerDO streamerDO = convertToStreamerDO(streamer);
        streamerMapper.insert(streamerDO);
    }

    public void insertFromJsonFile() {
        List<StreamerConfig> streamerConfigs = FileStoreUtil.loadFromFile(new File("/Users/caiwen/Documents/streamer.json "), new TypeReference<List<StreamerConfig>>() {
        });
        for (StreamerConfig config : streamerConfigs) {
            insert(config);
            StreamerConfig saved = getByName(config.getName());
            log.info("insert streamer: {} success, saved: {}", config.getName(), JSON.toJSONString(saved));

        }
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
                        Lists.newArrayList(StringUtils.split(streamerDO.getProcessPlugins(), ",")) : Lists.newArrayList())
                .uploadPlatforms(StringUtils.isNotBlank(streamerDO.getUploadPlatforms()) ?
                        Lists.newArrayList(StringUtils.split(streamerDO.getUploadPlatforms(), ",")) : Lists.newArrayList())
                .templateTitle(streamerDO.getTemplateTitle())
                .desc(streamerDO.getDesc())
                .tags(StringUtils.isNotBlank(streamerDO.getTags()) ?
                        Lists.newArrayList(StringUtils.split(streamerDO.getTags(), ",")) : Lists.newArrayList())
                .segMergeCnt(streamerDO.getSegMergeCnt())
                .coverFilePath(streamerDO.getCoverPath())
                .build();

        StreamerExtraDO streamerExtraDO = parseExtra(streamerDO.getExtra());
        if (streamerExtraDO != null) {
            BiliUploadInfoDO biliUploadInfo = streamerExtraDO.getBiliUploadInfo();
            if (biliUploadInfo != null) {
                config.setSource(biliUploadInfo.getSource());
                config.setTid(biliUploadInfo.getTid());
                config.setCover(biliUploadInfo.getCover());
            }

            DouyinUploadInfoDO douyinUploadInfo = streamerExtraDO.getDouyinUploadInfo();
            if (douyinUploadInfo != null) {
                config.setLocation(douyinUploadInfo.getLocation());
            }
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
                        .build())
                .douyinUploadInfo(DouyinUploadInfoDO.builder()
                        .location(streamerConfig.getLocation())
                        .build())
                .build();

        return StreamerDO.builder()
                .name(streamerConfig.getName())
                .roomUrl(streamerConfig.getRoomUrl())
                .recordType(streamerConfig.isRecordWhenOnline() ? "living" : "vod")
                .lastRecordTime(streamerConfig.getLastRecordTime())
                .expireTime(streamerConfig.getExpireTime())
                .lastVodCnt(streamerConfig.getLastVodCnt())
                .segMergeCnt(streamerConfig.getSegMergeCnt())
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
