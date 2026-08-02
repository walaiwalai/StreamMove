package com.sh.engine.processor.plugin.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.model.highlight.lol.LoLPicData;
import com.sh.engine.model.highlight.lol.LolSequenceScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.DETAIL_SNAPSHOT_DIR;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.KILL_DETAIL_CROP_EXPRESSION;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.OCR_BATCH_SIZE;
import static com.sh.engine.processor.plugin.lol.LolHighlightConstants.SNAP_INTERVAL_SECONDS;

/**
 * 将有序截图转换为 KDA 序列，并为序列中的精彩事件打分。
 */
@Component
@Slf4j
public class LolKdaSequenceAnalyzer {
    private static final Map<String, Integer> LAST_KILL_BY_STREAMER = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_DEATH_BY_STREAMER = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_ASSIST_BY_STREAMER = Maps.newConcurrentMap();
    private static final ThreadLocal<Integer> OCR_REQUEST_COUNT = new ThreadLocal<>();

    @Resource
    private LolOcrClient ocrClient;
    @Resource
    private LolTimestampFrameExtractor frameExtractor;

    public List<LoLPicData> analyze(List<File> snapshots, String recordPath) {
        File detailDirectory = new File(recordPath, DETAIL_SNAPSHOT_DIR);
        detailDirectory.mkdirs();
        OCR_REQUEST_COUNT.set(0);

        try {
            List<LoLPicData> kdaSequence = recognizeKdaSequence(snapshots, recordPath);
            return new LolSequenceScorer(kdaSequence).getSequences();
        } finally {
            int requestCount = OCR_REQUEST_COUNT.get();
            log.info("KDA OCR finished, snapshots: {}, requests: {}, avoided requests: {}",
                    snapshots.size(), requestCount, Math.max(0, snapshots.size() - requestCount));
            OCR_REQUEST_COUNT.remove();
        }
    }

    public List<LolKdaTimelinePoint> scoreTimeline(List<LolKdaTimelinePoint> timeline,
                                                    String recordPath) {
        if (timeline.isEmpty()) {
            return timeline;
        }
        File detailDirectory = new File(recordPath, DETAIL_SNAPSHOT_DIR);
        detailDirectory.mkdirs();

        for (int i = 1; i < timeline.size(); i++) {
            LolKdaTimelinePoint previousPoint = timeline.get(i - 1);
            LolKdaTimelinePoint currentPoint = timeline.get(i);
            recognizeHighlightDetail(
                    recordPath,
                    currentPoint.getSourceVideo(),
                    currentPoint.getSecondFromVideoStart(),
                    previousPoint.getPicData(),
                    currentPoint.getPicData());
        }

        List<LoLPicData> rawSequence = timeline.stream()
                .map(LolKdaTimelinePoint::getPicData)
                .collect(java.util.stream.Collectors.toList());
        List<LoLPicData> scoredSequence = new LolSequenceScorer(rawSequence).getSequences();
        for (int i = 0; i < timeline.size(); i++) {
            timeline.get(i).setPicData(scoredSequence.get(i));
        }
        return timeline;
    }

    private List<LoLPicData> recognizeKdaSequence(List<File> snapshots, String recordPath) {
        LoLPicData previousBatchEnd = LoLPicData.genBlank();
        List<LoLPicData> sequence = Lists.newArrayList();

        for (List<File> batch : Lists.partition(snapshots, OCR_BATCH_SIZE)) {
            LoLPicData currentBatchEnd = testParse(lastElement(batch));
            if (previousBatchEnd.compareKda(currentBatchEnd)) {
                appendRepeatedKda(sequence, previousBatchEnd, batch.size());
            } else {
                sequence.addAll(recognizeChangedBatch(
                        recordPath, previousBatchEnd, currentBatchEnd, batch));
            }
            previousBatchEnd = currentBatchEnd;
        }
        return sequence;
    }

    /**
     * K/D/A 在一局游戏内单调不减，因此端点发生变化时可以二分查找第一个变化截图。
     * 一批内可能发生多次变化：找到一次后，以新 KDA 为左端点继续查找。
     * OCR 无效或检测到回退（通常是换局）时，回退到逐张 OCR，避免错误套用单调假设。
     */
    private List<LoLPicData> recognizeChangedBatch(String recordPath,
                                                    LoLPicData previousBatchEnd,
                                                    LoLPicData currentBatchEnd,
                                                    List<File> batch) {
        if (!canUseBinarySearch(previousBatchEnd, currentBatchEnd)) {
            return recognizeEverySnapshot(recordPath, previousBatchEnd, batch);
        }

        Map<Integer, LoLPicData> recognizedByIndex = new HashMap<>();
        recognizedByIndex.put(batch.size() - 1, currentBatchEnd);
        List<LoLPicData> sequence = Lists.newArrayList();
        LoLPicData previous = previousBatchEnd;
        int fromIndex = 0;

        while (fromIndex < batch.size()) {
            LoLPicData rangeEnd = recognizedByIndex.get(batch.size() - 1);
            if (previous.compareKda(rangeEnd)) {
                appendRepeatedKda(sequence, previous, batch.size() - fromIndex);
                break;
            }

            int changeIndex = findFirstKdaChange(
                    previous, rangeEnd, fromIndex, batch, recognizedByIndex);
            if (changeIndex < 0) {
                log.info("KDA sequence is not monotonic in batch, fallback to sequential OCR, from: {}, to: {}",
                        JSON.toJSONString(previousBatchEnd), JSON.toJSONString(currentBatchEnd));
                return recognizeEverySnapshot(recordPath, previousBatchEnd, batch);
            }

            appendRepeatedKda(sequence, previous, changeIndex - fromIndex);
            LoLPicData changed = recognizedByIndex.get(changeIndex);
            recognizeHighlightDetail(recordPath, batch.get(changeIndex), previous, changed);
            sequence.add(changed);
            previous = changed;
            fromIndex = changeIndex + 1;
        }
        return sequence;
    }

    private List<LoLPicData> recognizeEverySnapshot(String recordPath,
                                                     LoLPicData previous,
                                                     List<File> snapshots) {
        List<LoLPicData> sequence = Lists.newArrayList();
        LoLPicData last = previous.copy();
        for (File snapshot : snapshots) {
            LoLPicData current = parseKdaWithFallback(snapshot);
            recognizeHighlightDetail(recordPath, snapshot, last, current);
            last = current;
            sequence.add(current);
        }
        return sequence;
    }

    private int findFirstKdaChange(LoLPicData previous,
                                   LoLPicData rangeEnd,
                                   int fromIndex,
                                   List<File> snapshots,
                                   Map<Integer, LoLPicData> recognizedByIndex) {
        int left = fromIndex;
        int right = snapshots.size() - 1;
        while (left < right) {
            int middle = left + (right - left) / 2;
            LoLPicData middleKda = recognizedByIndex.get(middle);
            if (middleKda == null) {
                middleKda = testParse(snapshots.get(middle));
                recognizedByIndex.put(middle, middleKda);
            }
            if (!isNonDecreasing(previous, middleKda) || !isNonDecreasing(middleKda, rangeEnd)) {
                return -1;
            }

            if (previous.compareKda(middleKda)) {
                left = middle + 1;
            } else {
                right = middle;
            }
        }

        LoLPicData changed = recognizedByIndex.get(left);
        if (changed == null) {
            changed = testParse(snapshots.get(left));
            recognizedByIndex.put(left, changed);
        }
        if (previous.compareKda(changed)
                || !isNonDecreasing(previous, changed)
                || !isNonDecreasing(changed, rangeEnd)) {
            return -1;
        }
        return left;
    }

    private boolean canUseBinarySearch(LoLPicData from, LoLPicData to) {
        return from.beValid() && to.beValid() && isNonDecreasing(from, to);
    }

    private boolean isNonDecreasing(LoLPicData from, LoLPicData to) {
        return from.beValid()
                && to.beValid()
                && to.getK() >= from.getK()
                && to.getD() >= from.getD()
                && to.getA() >= from.getA();
    }

    private void appendRepeatedKda(List<LoLPicData> sequence, LoLPicData kda, int count) {
        for (int i = 0; i < count; i++) {
            sequence.add(new LoLPicData(kda.getK(), kda.getD(), kda.getA()));
        }
    }

    private void recognizeHighlightDetail(String recordPath,
                                          File kdaSnapshot,
                                          LoLPicData previous,
                                          LoLPicData current) {
        File sourceVideo = VideoFileUtil.getSourceVideoFile(kdaSnapshot);
        int snapshotIndex = VideoFileUtil.getSnapshotIndex(kdaSnapshot);
        int startSecond = (snapshotIndex - 1) * SNAP_INTERVAL_SECONDS;
        recognizeHighlightDetail(recordPath, sourceVideo, startSecond, previous, current);
    }

    private void recognizeHighlightDetail(String recordPath,
                                          File sourceVideo,
                                          int startSecond,
                                          LoLPicData previous,
                                          LoLPicData current) {
        if (!hasKillOrAssistIncrease(previous, current)) {
            return;
        }

        File detailDirectory = new File(recordPath, DETAIL_SNAPSHOT_DIR);
        File detailSnapshot = frameExtractor.extract(
                sourceVideo, startSecond, KILL_DETAIL_CROP_EXPRESSION, detailDirectory);
        current.setHeroKADetail(ocrClient.recognizeKillDetail(detailSnapshot));
    }

    private boolean hasKillOrAssistIncrease(LoLPicData previous, LoLPicData current) {
        return previous.beValid()
                && current.beValid()
                && (current.getK() > previous.getK() || current.getA() > previous.getA());
    }

    private LoLPicData testParse(File snapshot) {
        List<Integer> kda = recognizeKda(snapshot);
        if (kda.size() < 3) {
            return LoLPicData.genInvalid();
        }
        return new LoLPicData(kda.get(0), kda.get(1), kda.get(2));
    }

    private LoLPicData parseKdaWithFallback(File snapshot) {
        List<Integer> kda = recognizeKda(snapshot);
        if (kda.size() < 3) {
            return loadLastRecognizedKda();
        }
        return cacheRecognizedKda(kda);
    }

    private List<Integer> recognizeKda(File snapshot) {
        if (snapshot.exists()) {
            OCR_REQUEST_COUNT.set(OCR_REQUEST_COUNT.get() + 1);
        }
        return ocrClient.recognizeKda(snapshot);
    }

    private LoLPicData loadLastRecognizedKda() {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        int kill = LAST_KILL_BY_STREAMER.getOrDefault(streamerName, -1);
        int death = LAST_DEATH_BY_STREAMER.getOrDefault(streamerName, -1);
        int assist = LAST_ASSIST_BY_STREAMER.getOrDefault(streamerName, -1);
        log.info("ocr error, will use last cache., last kda: {}/{}/{}.", kill, death, assist);
        return new LoLPicData(kill, death, assist);
    }

    private LoLPicData cacheRecognizedKda(List<Integer> kda) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        LAST_KILL_BY_STREAMER.put(streamerName, kda.get(0));
        LAST_DEATH_BY_STREAMER.put(streamerName, kda.get(1));
        LAST_ASSIST_BY_STREAMER.put(streamerName, kda.get(2));
        return new LoLPicData(kda.get(0), kda.get(1), kda.get(2));
    }

    private File lastElement(List<File> files) {
        return files.get(files.size() - 1);
    }
}
