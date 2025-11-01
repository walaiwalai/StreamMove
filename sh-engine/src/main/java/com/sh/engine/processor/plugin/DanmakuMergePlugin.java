package com.sh.engine.processor.plugin;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.model.ffmpeg.AssVideoMergeCmd;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DanmakuMergePlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;

    @Resource
    MsgSendService msgSendService;

    /**
     * 信号量：控制process方法的最大并发数（n）
     */
    private final Semaphore semaphore = new Semaphore(1, true);

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAMAKU_MERGE.getType();
    }

    @Override
    public boolean process(String recordPath) {
        List<File> assFiles = FileUtils.listFiles(new File(recordPath), new String[]{"ass"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(assFiles)) {
            return true;
        }

        for (File assFile : assFiles) {
            File mp4File = new File(assFile.getParent(), assFile.getName().replace(".ass", ".mp4"));
            if (mp4File.exists()) {
                continue;
            }
            File tsFile = new File(assFile.getParent(), assFile.getName().replace(".ass", ".ts"));
            if (!tsFile.exists()) {
                continue;
            }

            boolean acquired = semaphore.tryAcquire();
            if (!acquired) {
                throw new StreamerRecordException(ErrorEnum.OTHER_VIDEO_DAMAKU_MERGING);
            }

            try {
                AssVideoMergeCmd assVideoMergeCmd = new AssVideoMergeCmd(assFile, tsFile);
                assVideoMergeCmd.execute(10 * 3600);
                boolean success = false;
                if (assVideoMergeCmd.isNormalExit()) {
                    msgSendService.sendText("合并弹幕文件成功！路径为：" + mp4File.getAbsolutePath());
                    if (EnvUtil.isProd()) {
                        FileUtils.deleteQuietly(tsFile);
                    }
                    success = true;
                } else {
                    msgSendService.sendText("合并弹幕文件失败！路径为：" + mp4File.getAbsolutePath() + "。尝试兜底合成mp4文件");
                    success = videoMergeService.ts2Mp4(tsFile);
                }
            } finally {
                semaphore.release();
            }
        }
        return true;
    }
}
