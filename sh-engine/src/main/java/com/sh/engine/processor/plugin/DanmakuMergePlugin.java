package com.sh.engine.processor.plugin;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.AssVideoMergeCmd;
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
            String damakuFileName = RecordConstant.DAMAKU_FILE_PREFIX + assFile.getName().replace(".ass", ".mp4");
            File damakuFile = new File(assFile.getParent(), damakuFileName);
            if (damakuFile.exists()) {
                continue;
            }
            File mp4File = new File(assFile.getParent(), assFile.getName().replace(".ass", ".mp4"));
            if (!mp4File.exists()) {
                continue;
            }

            boolean acquired = semaphore.tryAcquire();
            if (!acquired) {
                throw new StreamerRecordException(ErrorEnum.OTHER_VIDEO_DAMAKU_MERGING);
            }

            try {
                AssVideoMergeCmd assVideoMergeCmd = new AssVideoMergeCmd(assFile, mp4File);
                assVideoMergeCmd.execute(10 * 3600);
                if (assVideoMergeCmd.isNormalExit()) {
                    msgSendService.sendText("合并弹幕文件成功！路径为：" + damakuFile.getAbsolutePath());
                    // 成功就刪除原始MP4文件
                    if (EnvUtil.isProd()) {
                        FileUtils.deleteQuietly(mp4File);
                    }
                } else {
                    msgSendService.sendText("合并弹幕文件失败！路径为：" + damakuFile.getAbsolutePath());
                    // 失敗就刪除合成的彈幕文件
                    if (EnvUtil.isProd()) {
                        FileUtils.deleteQuietly(damakuFile);
                    }
                }
            } finally {
                semaphore.release();
            }
        }
        return true;
    }
}
