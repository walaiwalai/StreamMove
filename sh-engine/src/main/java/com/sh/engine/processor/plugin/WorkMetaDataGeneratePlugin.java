package com.sh.engine.processor.plugin;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.processor.uploader.UploaderFactory;
import com.sh.engine.processor.uploader.meta.*;
import com.sh.engine.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 作品元数据生成，上传到各个平台需要填写的元数据
 *
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@Component
@Slf4j
public class WorkMetaDataGeneratePlugin implements VideoProcessPlugin {
    @Override
    public String getPluginName() {
        return ProcessPluginEnum.META_DATA_GEN.getType();
    }

    @Override
    public boolean process(String recordPath) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);

        // 1. 获取上传平台
        List<String> uploadPlatforms = streamerConfig.getUploadPlatforms();
        if (CollectionUtils.isEmpty(uploadPlatforms)) {
            return true;
        }

        // 2. 根据上传平台生成元数据
        for (String platform : uploadPlatforms) {
            saveMetaData(platform, streamerConfig, recordPath);
        }
        return true;
    }

    /**
     * 保存视频元数据
     *
     * @param platform
     * @param streamerConfig
     * @param recordPath
     */
    private void saveMetaData(String platform, StreamerConfig streamerConfig, String recordPath) {
        UploadPlatformEnum platformEnum = UploadPlatformEnum.of(platform);
        if (platformEnum == null) {
            return;
        }

        String metaFileName = UploaderFactory.getMetaFileName(platform);
        if (platformEnum == UploadPlatformEnum.BILI_CLIENT) {
            FileStoreUtil.saveToFile(new File(recordPath, metaFileName), buildMetaDataForBiliClient(streamerConfig, recordPath));
        } else if (platformEnum == UploadPlatformEnum.DOU_YIN) {
            FileStoreUtil.saveToFile(new File(recordPath, metaFileName), buildMetaDataForDouyin(streamerConfig, recordPath));
        } else if (platformEnum == UploadPlatformEnum.WECHAT_VIDEO || platformEnum == UploadPlatformEnum.WECHAT_VIDEO_V2) {
            FileStoreUtil.saveToFile(new File(recordPath, metaFileName), buildMetaDataForWechatVideo(streamerConfig, recordPath));
        } else if (platformEnum == UploadPlatformEnum.MEI_TUAN_VIDEO) {
            FileStoreUtil.saveToFile(new File(recordPath, metaFileName), buildMetaDataForMeituanVideo(streamerConfig, recordPath));
        }
    }

    private static WorkMetaData buildMetaDataForBiliClient(StreamerConfig streamerConfig, String recordPath) {
        BiliClientWorkMetaData metaData = new BiliClientWorkMetaData();
        String title = genTitle(streamerConfig, recordPath);
        metaData.setTitle(title);
        metaData.setDesc(streamerConfig.getDesc());
        metaData.setTags(streamerConfig.getTags());
        metaData.setCover(streamerConfig.getCover());
        metaData.setTid(streamerConfig.getTid());
        metaData.setSource(streamerConfig.getSource());
        metaData.setDynamic(Optional.ofNullable(streamerConfig.getDynamic()).orElse(title));
        return metaData;
    }

    private static WorkMetaData buildMetaDataForDouyin(StreamerConfig streamerConfig, String recordPath) {
        DouyinWorkMetaData metaData = new DouyinWorkMetaData();
        metaData.setTitle(genTitle(streamerConfig, recordPath));
        metaData.setDesc(streamerConfig.getDesc());
        metaData.setTags(streamerConfig.getTags());
        metaData.setLocation(streamerConfig.getLocation());
        metaData.setPreViewFilePath(streamerConfig.getCoverFilePath());
        return metaData;
    }

    private static WorkMetaData buildMetaDataForWechatVideo(StreamerConfig streamerConfig, String recordPath) {
        WechatVideoMetaData metaData = new WechatVideoMetaData();
        metaData.setTitle(genTitle(streamerConfig, recordPath));
        metaData.setDesc(streamerConfig.getDesc());
        metaData.setTags(streamerConfig.getTags());
        metaData.setPreViewFilePath(streamerConfig.getCoverFilePath());
        return metaData;
    }

    private static WorkMetaData buildMetaDataForMeituanVideo(StreamerConfig streamerConfig, String recordPath) {
        MeituanWorkMetaData metaData = new MeituanWorkMetaData();
        metaData.setTitle(genTitle(streamerConfig, recordPath));
        metaData.setDesc(streamerConfig.getDesc());
        metaData.setTags(streamerConfig.getTags());
        metaData.setPreViewFilePath(streamerConfig.getCoverFilePath());
        return metaData;
    }

    /**
     * 根据标题模板生成视频标题
     *
     * @param streamerConfig 配置
     * @param recordPath     保存路径名称
     * @return 视频标题
     */
    private static String genTitle(StreamerConfig streamerConfig, String recordPath) {
        String timeV = new File(recordPath).getName();
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", DateUtil.describeTime(timeV, DateUtil.YYYY_MM_DD_HH_MM_SS_V2));
        paramsMap.put("name", streamerConfig.getName());

        if (StringUtils.isNotBlank(streamerConfig.getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(streamerConfig.getTemplateTitle());
        } else {
            return streamerConfig.getName() + " " + timeV + " " + "录播";
        }
    }
}
