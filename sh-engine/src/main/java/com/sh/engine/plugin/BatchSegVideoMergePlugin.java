package com.sh.engine.plugin;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
@Slf4j
public class BatchSegVideoMergePlugin implements VideoProcessPlugin {
    private static final int BATCH_RECORD_TS_COUNT = 1200;
    @Resource
    private VideoMergeService videoMergeService;

    @Override
    public String getPluginName() {
        return "BATCH_SEG_MERGE";
    }

    @Override
    public boolean process(String recordPath) {
        // 只有录像才能进行合并
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        if (BooleanUtils.isNotFalse(streamerConfig.isRecordWhenOnline())) {
            return false;
        }

        Collection<File> tsFiles = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("ts"), null);
        if (CollectionUtils.isEmpty(tsFiles)) {
            return true;
        }

        int videoIndex = 1;
        int total = tsFiles.size();
        List<Integer> segIndexes = IntStream.rangeClosed(1, total)
                .boxed()
                .collect(Collectors.toList());

        for (List<Integer> batchIndexes : Lists.partition(segIndexes, BATCH_RECORD_TS_COUNT)) {
            File targetMergedVideo = new File(recordPath, "P" + videoIndex + ".mp4");
            List<String> segNames = batchIndexes.stream().map(i -> "seg-" + i + ".ts").collect(Collectors.toList());
            boolean success;
            if (targetMergedVideo.exists()) {
                log.info("merge video: {} existed, skip this batch", targetMergedVideo.getAbsolutePath());
                success = true;
            } else {
                // 合并视频
                success = videoMergeService.merge(segNames, targetMergedVideo);
            }

            if (success) {
                deleteSegs(segNames, recordPath);
            }
            videoIndex++;
        }

        return true;
    }

    private void deleteSegs(List<String> segNames, String dirName) {
        for (String segName : segNames) {
            File file = new File(dirName, segName);
            FileUtils.deleteQuietly(file);
        }
    }
}
