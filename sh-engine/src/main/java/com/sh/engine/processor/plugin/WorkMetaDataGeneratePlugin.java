package com.sh.engine.processor.plugin;

import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.FileStoreUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.processor.uploader.meta.BiliClientWorkMetaData;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import com.sh.engine.processor.uploader.meta.WorkMetaData;
import com.sh.engine.processor.uploader.UploaderFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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
        metaData.setPreViewFilePath(streamerConfig.getPreViewFilePath());
        return metaData;
    }

    /**
     * 根据标题模板生成视频标题
     * @param streamerConfig  配置
     * @param recordPath 保存路径名称
     * @return 视频标题
     */
    private static String genTitle(StreamerConfig streamerConfig, String recordPath) {
        String timeV = new File(recordPath).getName();
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", describeTime(timeV));
        paramsMap.put("name", streamerConfig.getName());

        if (StringUtils.isNotBlank(streamerConfig.getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(streamerConfig.getTemplateTitle());
        } else {
            return streamerConfig.getName() + " " + timeV + " " + "录播";
        }
    }


    /**
     * 根据给定的时间字符串返回年月日 + 时间段描述
     *
     * @param timeStr 时间字符串，格式为 "yyyy-MM-dd-HH-mm-ss"
     * @return 年月日 + 时间段描述
     */
    private static String describeTime(String timeStr) {
        LocalDateTime dateTime = LocalDateTime.parse(timeStr, DATE_FORMATTER);
        LocalTime time = dateTime.toLocalTime();
        String timeDescription = getTimeDescription(time);
        return dateTime.toLocalDate().format(DATE_ONLY_FORMATTER) + " " + timeDescription;
    }

    /**
     * 获取时间段描述
     *
     * @param time 时间
     * @return 时间段描述
     */
    private static String getTimeDescription(LocalTime time) {
        int hour = time.getHour();
        if (hour < 4) {
            return "凌晨";
        } else if (hour >= 5 && hour < 12) {
            return "早上";
        } else if (hour >= 12 && hour < 18) {
            return "中午";
        } else if (hour >= 18) {
            return "晚上";
        } else {
            return "下午";
        }
    }
}
