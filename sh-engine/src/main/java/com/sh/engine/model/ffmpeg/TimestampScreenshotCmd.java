package com.sh.engine.model.ffmpeg;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 使用输入侧 -ss 按绝对时间快速抽取单帧，不附加 fps 过滤器。
 */
public class TimestampScreenshotCmd extends AbstractCmd {

    public TimestampScreenshotCmd(File sourceVideo,
                                  File targetImage,
                                  int timestampSeconds,
                                  String cropExpression) {
        this(sourceVideo, targetImage, timestampSeconds, cropExpression, 0);
    }

    /**
     * @param preRollSeconds 大于 0 时先快速定位到目标时间之前，再解码该秒数精确到目标帧
     */
    public TimestampScreenshotCmd(File sourceVideo,
                                  File targetImage,
                                  int timestampSeconds,
                                  String cropExpression,
                                  int preRollSeconds) {
        super(buildCommand(sourceVideo, targetImage, timestampSeconds, cropExpression, preRollSeconds));
    }

    public boolean isEndNormal() {
        return isNormalExit();
    }

    private static String buildCommand(File sourceVideo,
                                       File targetImage,
                                       int timestampSeconds,
                                       String cropExpression,
                                       int preRollSeconds) {
        int inputSeekSecond = Math.max(0, timestampSeconds - preRollSeconds);
        int outputSeekSecond = timestampSeconds - inputSeekSecond;

        List<String> parameters = new ArrayList<>();
        parameters.add("ffmpeg");
        parameters.add("-y");
        parameters.add("-loglevel");
        parameters.add("error");
        parameters.add("-ss");
        parameters.add(String.format(Locale.ROOT, "%d", inputSeekSecond));
        parameters.add("-accurate_seek");
        parameters.add("-i");
        parameters.add(quote(sourceVideo));
        if (preRollSeconds > 0 && outputSeekSecond > 0) {
            parameters.add("-ss");
            parameters.add(String.format(Locale.ROOT, "%d", outputSeekSecond));
        }
        parameters.add("-an");
        parameters.add("-vf");
        parameters.add(quote(cropExpression));
        parameters.add("-frames:v");
        parameters.add("1");
        parameters.add("-q:v");
        parameters.add("5");
        parameters.add(quote(targetImage));
        return StringUtils.join(parameters, " ");
    }

    private static String quote(File file) {
        return quote(file.getAbsolutePath());
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    @Override
    protected void processOutputLine(String line) {
    }

    @Override
    protected void processErrorLine(String line) {
    }
}
