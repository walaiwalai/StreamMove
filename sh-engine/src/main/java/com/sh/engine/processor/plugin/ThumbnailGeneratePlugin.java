package com.sh.engine.processor.plugin;

import com.google.common.collect.Lists;
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
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        if (StringUtils.isBlank(coverFilePath)) {
            coverFilePath = RecordConstant.DEFAULT_THUMBNAIL_URL;
        }

        File coverFile = new File(coverFilePath);
        if (coverFile.exists()) {
            genThumbnail(recordPath, coverFile);
        }
        return true;
    }

    /**
     * 生成封面
     *
     * @param recordPath     录制路径
     * @param coverFile 封面本地文件
     */
    public void genThumbnail(String recordPath, File coverFile) {
        File thumbFile = new File(recordPath, RecordConstant.THUMBNAIL_FILE_NAME);
        File bgFile = new File(recordPath, "bg-thumbnail.jpg");

        VideoSizeDetectCmd detectCmd = new VideoSizeDetectCmd(coverFile.getAbsolutePath());
        detectCmd.execute(10, TimeUnit.SECONDS);
        int height = detectCmd.getHeight();

        // 生成背景图片
        PictureFileUtil.createTextOverlayImage(
                StringUtils.join(buildThumbTitle(recordPath), "\n"),
                detectCmd.getWidth(),
                detectCmd.getHeight(),
                (int) height / 10,
                bgFile);

        // 合并背景图片和封面
        String cmd = genThumbnailMergeCmd(bgFile, coverFile, thumbFile);
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(cmd);
        processCmd.execute(20, TimeUnit.SECONDS);
    }

    private List<String> buildThumbTitle(String recordPath) {
        String timeV = new File(recordPath).getName();

        List<String> res = Lists.newArrayList();
        LocalDateTime dateTime = LocalDateTime.parse(timeV, DateTimeFormatter.ofPattern(DateUtil.YYYY_MM_DD_HH_MM_SS_V2));
        res.add(dateTime.getYear() + "-" + dateTime.getMonthValue() + "-" + dateTime.getDayOfMonth() + " " + dateTime.getHour() + "时");
        res.add(StreamerInfoHolder.getCurStreamerName() + " 直播回放");
        return res;
    }

    public static String genThumbnailCmd(String backGroundPath, String targetFilePath, List<String> texts, int fontSize, String fontColor) {
        StringBuilder command = new StringBuilder();
        command.append("ffmpeg -i ");
        command.append("\"").append(backGroundPath).append("\"");
        command.append(" -vf \"");
        int[] yOffsets = calculateYOffsets(fontSize, texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i).replace("'", "\\'");
            int yOffset = yOffsets[i];
            command.append("drawtext=").append("text='").append(text).append("':x=(W - text_w)/2:y=(2 * H/5+").append(yOffset).append("):fontsize=").append(fontSize).append(":fontcolor=").append(fontColor);
            if (i < texts.size() - 1) {
                command.append(",");
            }
        }
        command.append("\" -frames:v 1 -update 1 ").append(targetFilePath);
        return command.toString();
    }

    private static String genThumbnailMergeCmd(File bgFile, File coverFile, File thumbFile) {
        StringBuilder command = new StringBuilder();
        command.append("ffmpeg -y -i ").append(bgFile.getAbsolutePath());
        command.append(" -i ").append(coverFile.getAbsolutePath());
        command.append(" -filter_complex ").append("\"[1:v]format=rgba,colorchannelmixer=aa=0.3[over];[0:v][over]overlay=0:0\"");
        command.append(" ").append(thumbFile.getAbsolutePath());
        return command.toString();
    }

    // 这个方法根据字体大小和行数计算合适的y轴偏移量
    private static int[] calculateYOffsets(int fontSize, int lineCount) {
        int[] yOffsets = new int[lineCount];
        int baseY = 0;
        int lineSpacing = (int) (fontSize * 1.1);
        for (int i = 0; i < lineCount; i++) {
            yOffsets[i] = baseY;
            baseY += lineSpacing;
        }
        return yOffsets;
    }

    public static void main( String[] args ) {
        ThumbnailGeneratePlugin plugin = new ThumbnailGeneratePlugin();
        plugin.genThumbnail("/Users/caiwen/Desktop/download/TheShy/2024-01-31-03-31-43", new File("/Users/caiwen/Desktop/jakons.jpg"));

    }
}
