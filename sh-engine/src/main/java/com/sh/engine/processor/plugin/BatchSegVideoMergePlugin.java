package com.sh.engine.processor.plugin;

import com.google.common.collect.Lists;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.service.process.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
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
    @Resource
    MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.BATCH_SEG_MERGE.getType();
    }

    @Override
    public boolean process(String recordPath) {
        // 只有录像才能进行合并
        Collection<File> tsFiles = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("ts"), null);
        if (CollectionUtils.isEmpty(tsFiles)) {
            return true;
        }

        int videoIndex = 1;
        List<Integer> sortedIndexes = tsFiles.stream()
                .map(file -> VideoFileUtil.genIndex(file.getName()))
                .sorted()
                .collect(Collectors.toList());
        int endIndex = sortedIndexes.get(sortedIndexes.size() - 1);

        List<Integer> segIndexes = IntStream.rangeClosed(1, endIndex)
                .boxed()
                .collect(Collectors.toList());

        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        int batchSize;
        if (streamerConfig.getMaxMergeSize() > 0) {
            // 计算搜友的tsFiles的size综合
            long totalSize = tsFiles.stream()
                    .mapToLong(File::length)
                    .sum();
            batchSize = tsFiles.size() / ((int) Math.ceil((double) totalSize / 1024 / 1024 / streamerConfig.getMaxMergeSize()));
        } else {
            batchSize = Math.max(streamerConfig.getSegMergeCnt(), BATCH_RECORD_TS_COUNT);
        }
        for (List<Integer> batchIndexes : Lists.partition(segIndexes, batchSize)) {
            File targetMergedVideo = new File(recordPath, "P" + videoIndex + ".mp4");
            List<String> segNames = batchIndexes.stream()
                    .map(i -> new File(recordPath, VideoFileUtil.genSegName(i)))
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList());
            boolean success;
            if (targetMergedVideo.exists()) {
                log.info("merge video: {} existed, skip this batch", targetMergedVideo.getAbsolutePath());
                success = true;
            } else {
                // 合并视频
                success = videoMergeService.concatByDemuxer(segNames, targetMergedVideo);
            }

            if (success) {
                if (EnvUtil.isProd()) {
                    deleteSegs(segNames);
                }
                msgSendService.sendText("合并视频完成！路径为：" + targetMergedVideo.getAbsolutePath());
            } else {
                msgSendService.sendText("合并视频失败！路径为：" + targetMergedVideo.getAbsolutePath());
            }
            videoIndex++;
        }

        return true;
    }

    private void deleteSegs(List<String> segNames) {
        for (String segName : segNames) {
            File file = new File(segName);
            FileUtils.deleteQuietly(file);
        }
    }
}
