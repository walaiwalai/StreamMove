package com.sh.engine.processor.plugin;

import com.alibaba.fastjson.JSON;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.highlight.SnapshotVideoInterval;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.processor.plugin.lol.LolHighlightIntervalSelector;
import com.sh.engine.processor.plugin.lol.LolKdaTimelineService;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LOL 录播精彩片段剪辑流程。
 *
 * 具体的截图、OCR、序列分析和区间选择由独立组件完成；插件只负责编排处理步骤。
 */
@Component
@Slf4j
public class LoLVodHighLightCutV2Plugin implements VideoProcessPlugin {
    private static final Pattern SOURCE_VIDEO_PATTERN = Pattern.compile("(?i)^P\\d+\\.mp4$");

    @Resource
    private LolKdaTimelineService timelineService;
    @Resource
    private LolHighlightIntervalSelector intervalSelector;
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.LOL_HL_VOD_CUT_V2.getType();
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (highlightFile.exists()) {
            log.info("highlight file already existed, will skip, path: {}", recordPath);
            return true;
        }

        List<File> sourceVideos = findSourceVideos(recordPath);
        if (CollectionUtils.isEmpty(sourceVideos)) {
            log.info("empty mp4 video file, will skip, path: {}", recordPath);
            return true;
        }

        List<LolKdaTimelinePoint> timeline = timelineService.buildScoredTimeline(
                recordPath, sourceVideos);
        if (CollectionUtils.isEmpty(timeline)) {
            return true;
        }

        List<SnapshotVideoInterval> highlightIntervals = intervalSelector.select(timeline);
        log.info("find topNIntervals: {}", JSON.toJSONString(highlightIntervals));
        if (CollectionUtils.isEmpty(highlightIntervals)) {
            log.info("no highlight interval found, skip video merge, path: {}", recordPath);
            return true;
        }

        boolean success = mergeHighlights(highlightFile, highlightIntervals);
        notifyMergeResult(highlightFile, success);
        return success;
    }

    @Override
    public int getMaxProcessParallel() {
        return 2;
    }

    private List<File> findSourceVideos(String recordPath) {
        return FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
                .stream()
                .filter(file -> SOURCE_VIDEO_PATTERN.matcher(file.getName()).matches())
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
    }

    private boolean mergeHighlights(File highlightFile,
                                    List<SnapshotVideoInterval> highlightIntervals) {
        String recordTime = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(recordTime, DateUtil.YYYY_MM_DD_HH_MM_SS_V2)
                + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        List<VideoInterval> videoIntervals = new ArrayList<>(highlightIntervals);
        return videoMergeService.mergeVerticalWithCover(videoIntervals, highlightFile, title);
    }

    private void notifyMergeResult(File highlightFile, boolean success) {
        String messagePrefix = success ? "合并视频完成！路径为：" : "合并视频失败！路径为：";
        msgSendService.sendText(messagePrefix + highlightFile.getAbsolutePath());
    }
}
