package com.sh.engine.service;

import cn.hutool.core.io.file.FileNameUtil;
import com.google.common.collect.Lists;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.CommandUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/***
 * https://trac.ffmpeg.org/wiki/Concatenate#demuxer
 * todo 代做事项
 * 2. 背景音乐
 * 3. 视频开场和结尾加上动画
 */
@Component
@Slf4j
public class VideoMergeServiceImpl implements VideoMergeService {
    @Resource
    MsgSendService msgSendService;

    /**
     * 淡出时间（s）
     */
    private static final int FADE_DURATION = 1;

    @Override
    public boolean concatByDemuxer(List<String> mergedFileNames, File targetVideo) {
        File mergeListFile = new File(targetVideo.getParent(), FileNameUtil.getPrefix(targetVideo) + "-merge.txt");
        List<String> lines = mergedFileNames.stream()
                .map(name -> {
                    File segFile = new File(name);
                    if (!segFile.exists()) {
                        return null;
                    }
                    return "file " + segFile.getAbsolutePath();
                })
                .filter(Objects::nonNull)
                .map(s -> {
                    if (EnvUtil.isProd()) {
                        return s;
                    } else {
                        return s.replace("\\", "\\\\");
//                        return s;
                    }
                })
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(lines)) {
            return false;
        }

        // 1. 写入merge.txt
        try {
            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
        String command = "ffmpeg -y -loglevel error -f concat -safe 0 -i " + mergeListFile.getAbsolutePath() +
                " -c:v copy -c:a copy " + targetPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);

        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        return resCode == 0;
    }

    @Override
    public boolean concatByProtocol(List<String> mergedFileNames, File targetVideo) {
        if (CollectionUtils.isEmpty(mergedFileNames)) {
            return false;
        }
        String targetPath = targetVideo.getAbsolutePath();
        String cmd = "ffmpeg -loglevel error -i " + "concat:" + StringUtils.join(mergedFileNames, "|") + " -c copy " + targetPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(cmd);
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            msgSendService.sendText("按照concat协议合并视频完成！路径为：" + targetPath);
            return true;
        } else {
            msgSendService.sendText("按照concat协议合并视频失败！路径为：" + targetPath);
            return false;
        }
    }

    @Override
    public boolean mergeMultiWithFade(List<List<String>> intervals, File targetVideo) {
        // 单独一个不处理
        if (intervals.size() == 1) {
            return concatByDemuxer(intervals.get(0), targetVideo);
        }

        // 先合并小的seg文件
        List<String> segVideoPaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            if (i == 0) {
                segVideoPaths.addAll(intervals.get(i));
                continue;
            }

            // 针对片段开头的视频做淡入操作
            List<String> interval = intervals.get(i);
            for (int j = 0; j < interval.size(); j++) {
                if (j == 0) {
                    segVideoPaths.add(doFade(new File(interval.get(j))));
                } else {
                    segVideoPaths.add(interval.get(j));
                }
            }
        }
        return concatByProtocol(segVideoPaths, targetVideo);
    }

    @Override
    public boolean mergeMultiWithFadeV2(List<List<String>> intervals, File targetVideo) {
        // 单独一个不处理
        if (intervals.size() == 1) {
            return concatByDemuxer(intervals.get(0), targetVideo);
        }
        File tmpSaveDir = new File(targetVideo.getParent(), "tmp");
        if (!tmpSaveDir.exists()) {
            tmpSaveDir.mkdir();
        }

        List<String> mergedPaths = Lists.newArrayList();
        for (int i = 0; i < intervals.size(); i++) {
            File tmpFile = new File((tmpSaveDir), "tmp-" + (i + 1) + ".ts");
            boolean success = concatByDemuxer(intervals.get(i), tmpFile);
            if (success) {
                mergedPaths.add(doFade(tmpFile));
            }
        }

        boolean success = concatByDemuxer(mergedPaths, targetVideo);
        if (success) {
            FileUtils.deleteQuietly(tmpSaveDir);
        }
        return success;
    }

    /**
     * @param oldVideoFile
     * @return
     */
    private String doFade(File oldVideoFile) {
        File fadedSeg = new File(oldVideoFile.getParent(), FileNameUtil.getPrefix(oldVideoFile) + "-fade.ts");
        String fadedPath = fadedSeg.getAbsolutePath();
        String cmd = "ffmpeg -y -loglevel error -i " + oldVideoFile.getAbsolutePath() + " -vf fade=t=in:st=0:d=" + FADE_DURATION + " -c:v libx264 -crf 24 -preset superfast -c:a aac " + fadedPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(cmd);
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("do fade success, path: {}", fadedPath);
            return fadedPath;
        } else {
            log.info("do fade fail, will use origin video, path: {}, resCode: {}", fadedPath, resCode);
            return oldVideoFile.getAbsolutePath();
        }
    }

    /**
     * @param videoFilePaths
     * @return
     */
    private static String genFadeConcatFilterCmd(List<String> videoFilePaths, File targetVideo) {
        StringBuilder ffmpegCommand = new StringBuilder("-y ");
        // 添加输入文件路径
        for (int i = 0; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("-i \"").append(videoFilePaths.get(i)).append("\" ");
        }

        // 视频淡入/淡出效果（假设对每个视频片段都做淡入）
        ffmpegCommand.append("-filter_complex \"");
        for (int i = 1; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[")
                    .append(i)
                    .append(":v]fade=t=in:st=0:d=" + FADE_DURATION + "[v")
                    .append(i)
                    .append("];");
        }

        // 合并视频流
        ffmpegCommand.append("[0:v]");
        for (int i = 1; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[");
            ffmpegCommand.append("v")
                    .append(i)
                    .append("]");
        }
        ffmpegCommand.append("concat=n=").append(videoFilePaths.size()).append(":v=1:a=0[outv];");

        // 合并音频流
        for (int i = 0; i < videoFilePaths.size(); i++) {
            ffmpegCommand.append("[").append(i).append(":a]");
        }
        ffmpegCommand.append("concat=n=").append(videoFilePaths.size()).append(":v=0:a=1[outa]\" ");

        // 输出映射
        ffmpegCommand.append("-map \"[outv]\" ");
        ffmpegCommand.append("-map \"[outa]\" ");

        // 编码参数设置
        ffmpegCommand.append("-c:v libx264 -crf 24 -preset superfast ");
        ffmpegCommand.append("-c:a aac ");

        // 输出文件路径
        ffmpegCommand.append(targetVideo.getAbsolutePath());

        return ffmpegCommand.toString();
    }
}

