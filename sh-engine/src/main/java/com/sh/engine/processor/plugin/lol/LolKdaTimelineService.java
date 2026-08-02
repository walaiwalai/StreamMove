package com.sh.engine.processor.plugin.lol;

import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.model.highlight.lol.LoLPicData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAP_INTERVAL_SECONDS;

/**
 * 构建并评分 KDA 时间线；稀疏扫描无法可靠完成时自动回退到原密集流程。
 */
@Component
@Slf4j
public class LolKdaTimelineService {
    @Resource
    private LolAdaptiveKdaScanner adaptiveScanner;
    @Resource
    private LolHighlightSnapshotService snapshotService;
    @Resource
    private LolKdaSequenceAnalyzer sequenceAnalyzer;

    public List<LolKdaTimelinePoint> buildScoredTimeline(String recordPath, List<File> videos) {
        try {
            List<LolKdaTimelinePoint> timeline = adaptiveScanner.scan(recordPath, videos);
            return sequenceAnalyzer.scoreTimeline(timeline, recordPath);
        } catch (LolAdaptiveScanException e) {
            log.warn("adaptive KDA scan unavailable, fallback to dense screenshots, path: {}",
                    recordPath, e);
            return buildDenseTimeline(recordPath, videos);
        }
    }

    private List<LolKdaTimelinePoint> buildDenseTimeline(String recordPath, List<File> videos) {
        List<File> snapshots = snapshotService.createKdaSnapshots(recordPath, videos);
        List<LoLPicData> sequence = sequenceAnalyzer.analyze(snapshots, recordPath);
        List<LolKdaTimelinePoint> timeline = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            File snapshot = snapshots.get(i);
            timeline.add(new LolKdaTimelinePoint(
                    VideoFileUtil.getSourceVideoFile(snapshot),
                    (VideoFileUtil.getSnapshotIndex(snapshot) - 1) * SNAP_INTERVAL_SECONDS,
                    sequence.get(i)));
        }
        return timeline;
    }
}
