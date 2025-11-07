package com.sh.engine.processor.plugin;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 视频封面生成插件
 *
 * @Author caiwen
 * @Date 2025 01 12 17 48
 **/
@Component
@Slf4j
public class ThumbnailGeneratePlugin implements VideoProcessPlugin {
    @Override
    public String getPluginName() {
        return ProcessPluginEnum.THUMBNAIL_GEN.getType();
    }

    @Override
    public boolean process(String recordPath) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        String coverFilePath = streamerConfig.getCoverFilePath();

        File coverFile;
        if (StringUtils.isBlank(coverFilePath)) {
            coverFile = new File(RecordConstant.DEFAULT_THUMBNAIL_URL);
        } else {
            File tmpFile = new File(coverFilePath);
            if (tmpFile.exists()) {
                coverFile = new File(coverFilePath);
            } else {
                coverFile = new File(RecordConstant.DEFAULT_THUMBNAIL_URL);
            }
        }

        genThumbnail(recordPath, coverFile);
        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 99;
    }

    /**
     * 生成封面
     *
     * @param recordPath 录制路径
     * @param coverFile  封面本地文件
     */
    public void genThumbnail(String recordPath, File coverFile) {
        File thumbFile = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME);

        // 生成背景图片
        PictureFileUtil.createTextOnImage(coverFile, thumbFile, buildThumbTitle(recordPath));
    }

    private List<String> buildThumbTitle(String recordPath) {
        String timeV = new File(recordPath).getName();

        List<String> res = Lists.newArrayList();
        LocalDateTime dateTime = LocalDateTime.parse(timeV, DateTimeFormatter.ofPattern(DateUtil.YYYY_MM_DD_HH_MM_SS_V2));
        res.add(dateTime.getYear() + "-" + dateTime.getMonthValue() + "-" + dateTime.getDayOfMonth() + " " + dateTime.getHour() + "时");
        res.add(StreamerInfoHolder.getCurStreamerName() + " 直播回放");
        return res;
    }
}
