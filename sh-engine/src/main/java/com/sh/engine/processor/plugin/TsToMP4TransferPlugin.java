package com.sh.engine.processor.plugin;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.LocalCacheManager;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2025 08 09 00 33
 **/
@Component
@Slf4j
public class TsToMP4TransferPlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    MsgSendService msgSendService;
    /**
     * 信号量：控制process方法的最大并发数（n）
     */
    private final Semaphore semaphore = new Semaphore(2, true);

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.TS_2_MP4_TRANSFER.getType();
    }

    @Override
    public boolean process(String recordPath) {
        // 只有录像才能进行合并
        List<File> tsFiles = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("ts"), null)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(tsFiles)) {
            return true;
        }
        for (File tsFile : tsFiles) {
            File mp4File = new File(tsFile.getParent(), tsFile.getName().replace(".ts", ".mp4"));
            if (mp4File.exists()) {
                continue;
            }
            boolean acquired = semaphore.tryAcquire();
            if (!acquired) {
                throw new StreamerRecordException(ErrorEnum.OTHER_VIDEO_CONVERTING);
            }

            boolean success = false;
            try {
                success = videoMergeService.ts2Mp4(tsFile);
            } finally {
                semaphore.release();
            }

            if (success) {
                if (EnvUtil.isProd()) {
                    FileUtils.deleteQuietly(tsFile);
                }
            } else {
                msgSendService.sendText("ts文件转换成MP4失败！路径为：" + tsFile.getAbsolutePath());
            }
        }
        return true;
    }
}
