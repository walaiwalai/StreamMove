package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSON;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import com.sh.engine.model.highlight.core.HighlightOutputMode;
import com.sh.engine.model.highlight.core.HighlightProcessContext;
import com.sh.engine.model.highlight.core.ScoredVideoInterval;
import com.sh.engine.processor.plugin.VideoProcessPlugin;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 游戏高光插件的通用编排骨架。游戏插件只提供时间线、剪辑策略和输出方向。
 */
@Slf4j
public abstract class AbstractHighlightCutPlugin implements VideoProcessPlugin {
    private static final Pattern SOURCE_VIDEO_PATTERN = Pattern.compile("(?i)^P\\d+\\.mp4$");

    @Resource
    private HighlightIntervalPlanner intervalPlanner;
    @Resource
    private HighlightAdvertisementMaskDetector advertisementMaskDetector;
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private MsgSendService msgSendService;

    /**
     * 依次执行源视频发现、游戏事件识别、区间规划、广告蒙层和单文件输出。
     */
    @Override
    public final boolean process(String recordPath) {
        List<File> sourceVideos = findSourceVideos(recordPath);
        if (CollectionUtils.isEmpty(sourceVideos)) {
            log.info("empty source mp4 video, skip highlight plugin: {}, path: {}",
                    getPluginName(), recordPath);
            return true;
        }

        File targetVideo = new File(recordPath, RecordConstant.HL_VIDEO);
        if (targetVideo.isFile()) {
            log.info("highlight output already existed, skip plugin: {}, path: {}",
                    getPluginName(), recordPath);
            return true;
        }
        File workDirectory = new File(
                recordPath, ".highlight-work/" + getPluginName().toLowerCase(Locale.ROOT));
        HighlightProcessContext context = new HighlightProcessContext(
                recordPath, sourceVideos, workDirectory);

        List<HighlightEvent> timeline = timelineProvider().buildScoredTimeline(context);
        if (CollectionUtils.isEmpty(timeline)) {
            log.info("empty highlight timeline, skip plugin: {}, path: {}",
                    getPluginName(), recordPath);
            return true;
        }
        List<ScoredVideoInterval> intervals = intervalPlanner.select(timeline, cutPolicy());
        log.info("highlight intervals, plugin: {}, intervals: {}",
                getPluginName(), JSON.toJSONString(intervals));
        if (CollectionUtils.isEmpty(intervals)) {
            return true;
        }

        HighlightMaskPlan maskPlan = advertisementMaskDetector.detect(intervals, workDirectory);
        String recordTime = targetVideo.getParentFile().getName();
        String title = DateUtil.describeTime(recordTime, DateUtil.YYYY_MM_DD_HH_MM_SS_V2)
                + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        boolean success = mergeIntervals(intervals, targetVideo, title, maskPlan);
        notifyResult(success, targetVideo);
        return success;
    }

    @Override
    public int getMaxProcessParallel() {
        return 2;
    }

    protected abstract HighlightTimelineProvider timelineProvider();

    protected abstract HighlightCutPolicy cutPolicy();

    protected abstract HighlightOutputMode outputMode();

    /**
     * 根据游戏声明的横竖版模式调用现有视频合并服务。
     */
    private boolean mergeIntervals(List<ScoredVideoInterval> intervals,
                                   File targetVideo,
                                   String title,
                                   HighlightMaskPlan maskPlan) {
        List<VideoInterval> videoIntervals = new ArrayList<>(intervals);
        if (outputMode() == HighlightOutputMode.MERGED_VERTICAL_WITH_COVER) {
            return videoMergeService.mergeVerticalWithCover(
                    videoIntervals, targetVideo, title, maskPlan);
        }
        return videoMergeService.mergeWithCover(videoIntervals, targetVideo, title, maskPlan);
    }

    private List<File> findSourceVideos(String recordPath) {
        return FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
                .stream()
                .filter(file -> SOURCE_VIDEO_PATTERN.matcher(file.getName()).matches())
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
    }

    private void notifyResult(boolean success, File targetVideo) {
        String prefix = success ? "高光视频处理完成：\n" : "高光视频处理失败：\n";
        msgSendService.sendText(prefix + (success ? targetVideo.getAbsolutePath() : ""));
    }
}
