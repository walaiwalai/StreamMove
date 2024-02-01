package com.sh.engine.service;

import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.util.CommandUtil;
import lombok.extern.slf4j.Slf4j;
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

@Component
@Slf4j
public class VideoMergeServiceImpl implements VideoMergeService {
    @Resource
    MsgSendService msgSendService;

    @Override
    public boolean mergeVideos(List<String> mergedFileNames, File targetVideo) {
        File mergeListFile = new File(targetVideo.getParent(), "merge.txt");
        List<String> lines = mergedFileNames.stream()
                .map(name -> {
                    File segFile = new File(targetVideo.getParent(), name);
                    if (!segFile.exists()) {
                        return null;
                    }
                    return "file " + segFile.getAbsolutePath();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 1. 写入merge.txt
        try {
            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
        } catch (IOException e) {
            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
        }

        // 2. 使用FFmpeg合并视频
        String targetPath = targetVideo.getAbsolutePath();
        String command = "-f concat -safe 0 -i " + mergeListFile.getAbsolutePath() + " -c:v libx264 -crf 24 -preset superfast -c:a libfdk_aac -r 30 " + targetPath;
        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);

        msgSendService.send("开始合并压缩视频... 路径为：" + targetVideo.getAbsolutePath());
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            msgSendService.send("合并压缩视频完成！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video success, path: {}", targetPath);
            return true;
        } else {
            msgSendService.send("压缩视频失败！路径为：" + targetVideo.getAbsolutePath());
            log.info("merge video fail, path: {}", targetPath);
            return false;
        }
    }
}
