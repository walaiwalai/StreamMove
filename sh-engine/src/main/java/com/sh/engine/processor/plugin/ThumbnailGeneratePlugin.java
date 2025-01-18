package com.sh.engine.processor.plugin;

import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.PictureFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import com.sh.engine.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

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

        genThumbnail(recordPath, streamerConfig);
        return true;
    }

    /**
     * 生成封面
     *
     * @param recordPath     录制路径
     * @param streamerConfig 直播配置
     */
    private void genThumbnail(String recordPath, StreamerConfig streamerConfig) {
        String outputImagePath = new File(recordPath, "thumbnail-title.jpg").getAbsolutePath();
        String targetThumbPath = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME).getAbsolutePath();

        String querySizeCmd = "ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 " + streamerConfig.getPreViewFilePath();
        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(querySizeCmd);
        detectCmd.execute();
        int width = detectCmd.getWidth();
        int height = detectCmd.getHeight();
        PictureFileUtil.createTextOverlayImage(buildThumbTitle(recordPath), width, height, 200, outputImagePath);

        // 合并封面
        String cmd = "ffmpeg -y -loglevel error -i " + streamerConfig.getPreViewFilePath() + " -i " + outputImagePath +
                " -filter_complex \"overlay=W-w:H-h\" " + targetThumbPath;
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute();
    }

    private String buildThumbTitle(String recordPath) {
        String timeV = new File(recordPath).getName();
        LocalDateTime dateTime = LocalDateTime.parse(timeV, DateTimeFormatter.ofPattern(DateUtil.YYYY_MM_DD_HH_MM_SS_V2));
        return dateTime.getYear() + "-" + dateTime.getMonthValue() + "-" + dateTime.getDayOfMonth() + "-" + dateTime.getHour() + "时 \n直播回放";
    }
}
