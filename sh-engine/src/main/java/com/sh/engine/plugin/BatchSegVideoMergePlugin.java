package com.sh.engine.plugin;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.RecordContext;
import com.sh.engine.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
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
    public boolean process(RecordContext context) {
        // 只有录像才能进行合并
        String name = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        if (BooleanUtils.isNotTrue(streamerConfig.isRecordWhenOnline())) {
            return false;
        }

        int videoIndex = 1;
        String dirName = StreamerInfoHolder.getCurRecordPath();
        List<Integer> segIndexes = IntStream.rangeClosed(1, context.getLivingStreamer().getTsRecordInfo().getCount())
                .boxed()
                .collect(Collectors.toList());

        for (List<Integer> batchIndexes : Lists.partition(segIndexes, BATCH_RECORD_TS_COUNT)) {
            File targetMergedVideo = new File(dirName, "P" + videoIndex + ".mp4");
            if (targetMergedVideo.exists()) {
                log.info("merge video: {} existed, skip this batch", targetMergedVideo.getAbsolutePath());
                videoIndex++;
                continue;
            }

            // 合并视频
            List<String> segNames = batchIndexes.stream().map(i -> "seg-" + i + ".ts").collect(Collectors.toList());
            videoMergeService.merge(segNames, targetMergedVideo);
            videoIndex++;
        }
        return true;
    }
}
