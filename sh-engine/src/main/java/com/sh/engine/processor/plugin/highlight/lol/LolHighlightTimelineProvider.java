package com.sh.engine.processor.plugin.highlight.lol;

import com.alibaba.fastjson.JSON;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.HighlightProcessContext;
import com.sh.engine.model.highlight.core.InMemoryVideoFrame;
import com.sh.engine.model.highlight.lol.LOLHeroPositionEnum;
import com.sh.engine.model.highlight.lol.LoLPicData;
import com.sh.engine.model.highlight.lol.LolKdaTimelinePoint;
import com.sh.engine.processor.plugin.highlight.FfmpegFrameExtractor;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import com.sh.engine.processor.plugin.highlight.HighlightTimelineProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.sh.engine.constant.RecordConstant.KDA_SEQ_WINDOW_SIZE;

/**
 * LoL 高光分析的业务入口：扫描 KDA、修正 OCR 抖动、识别击杀详情并输出通用高光事件。
 *
 * <p>上层插件只依赖这个类；KDA 截帧和 OCR 细节由 {@link LolAdaptiveKdaScanner} 处理。</p>
 */
@Component
@Slf4j
public class LolHighlightTimelineProvider implements HighlightTimelineProvider {
    public static final String SELF_KILL_EVENT = "SELF_KILL";
    private static final String KILL_DETAIL_CROP_EXPRESSION =
            "crop=270:290:in_w*86/100:in_h*3/16";
    private static final float KILL_GAIN = 6.0f;
    private static final float ASSIST_GAIN = 2.0f;
    private static final float DEATH_PENALTY = 3.0f;
    private static final float EXTRA_KILL_COMBO_GAIN = 2.0f;
    private static final float MAX_DETAIL_GAIN = 10.0f;

    @Resource
    private LolAdaptiveKdaScanner adaptiveScanner;
    @Resource
    private FfmpegFrameExtractor frameExtractor;
    @Resource
    private HighlightOcrClient ocrClient;

    /**
     * 将源视频转换为统一高光事件。KDA 不变且得分为 0 的逻辑点不会输出事件。
     *
     * @param context 高光处理上下文
     * @return 已评分的 LoL 高光事件
     */
    @Override
    public List<HighlightEvent> buildScoredTimeline(HighlightProcessContext context) {
        List<LolKdaTimelinePoint> rawTimeline = adaptiveScanner.scan(
                context.getRecordPath(), context.getSourceVideos());
        recognizeKillDetails(rawTimeline);
        List<LolKdaTimelinePoint> timeline = correctTimeline(rawTimeline);

        List<HighlightEvent> events = new ArrayList<>();
        for (int i = 1; i < timeline.size(); i++) {
            LolKdaTimelinePoint previous = timeline.get(i - 1);
            LolKdaTimelinePoint current = timeline.get(i);
            HighlightEvent event = createEvent(previous, current);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }

    /**
     * 仅在击杀或助攻增加时读取右上角击杀栏，避免对所有 4 秒逻辑点重复检测。
     */
    private void recognizeKillDetails(List<LolKdaTimelinePoint> timeline) {
        for (int i = 1; i < timeline.size(); i++) {
            LolKdaTimelinePoint previous = timeline.get(i - 1);
            LolKdaTimelinePoint current = timeline.get(i);
            if (!hasKillOrAssistIncrease(previous.getPicData(), current.getPicData())) {
                continue;
            }
            File sourceVideo = current.getSourceVideo();
            int second = current.getSecondFromVideoStart();
            InMemoryVideoFrame frame = frameExtractor.extract(
                    sourceVideo, second, KILL_DETAIL_CROP_EXPRESSION);
            current.getPicData().setHeroKADetail(ocrClient.recognizeKillDetail(
                    frame.getJpegData(),
                    String.format("%s@%06d-kill-detail.jpg", sourceVideo.getName(), second)));
        }
    }

    private boolean hasKillOrAssistIncrease(LoLPicData previous, LoLPicData current) {
        return previous.beValid()
                && current.beValid()
                && (current.getK() > previous.getK() || current.getA() > previous.getA());
    }

    /**
     * 使用滑动窗口修正夹在两个有效值之间的空 OCR，以及明显回退或突增的孤立误识别。
     * 修正后的列表是新时间线，不修改扫描器返回的时间点对象。
     */
    private List<LolKdaTimelinePoint> correctTimeline(List<LolKdaTimelinePoint> timeline) {
        List<LoLPicData> rawKda = new ArrayList<>();
        for (LolKdaTimelinePoint point : timeline) {
            rawKda.add(point.getPicData());
        }
        List<LoLPicData> correctedKda = correctKdaSequence(rawKda);
        List<LolKdaTimelinePoint> correctedTimeline = new ArrayList<>();
        for (int i = 0; i < timeline.size(); i++) {
            LolKdaTimelinePoint point = timeline.get(i);
            correctedTimeline.add(new LolKdaTimelinePoint(
                    point.getSourceVideo(), point.getSecondFromVideoStart(), correctedKda.get(i)));
        }
        return correctedTimeline;
    }

    private List<LoLPicData> correctKdaSequence(List<LoLPicData> sequence) {
        List<LoLPicData> corrected = new ArrayList<>();
        LinkedList<LoLPicData> window = new LinkedList<>();
        for (LoLPicData current : sequence) {
            window.add(current);
            correctInWindow(window);
            if (window.size() > KDA_SEQ_WINDOW_SIZE) {
                corrected.add(window.removeFirst());
            }
        }
        corrected.addAll(window);
        return corrected;
    }

    private void correctInWindow(LinkedList<LoLPicData> window) {
        if (window.size() <= 2) {
            return;
        }
        boolean firstBlank = window.getFirst().beBlank();
        boolean lastBlank = window.getLast().beBlank();
        LoLPicData previous = LoLPicData.genBlank();
        for (int i = 0; i < window.size(); i++) {
            LoLPicData current = window.get(i);
            if (shouldCorrect(current, previous, firstBlank, lastBlank)) {
                current = copyPicData(previous);
                window.set(i, current);
            }
            previous = copyPicData(current);
        }
    }

    /**
     * 同一局 KDA 不会回退；单次最多接受 K/A +5、D +2，超出视为 OCR 抖动。
     */
    private boolean shouldCorrect(LoLPicData current,
                                  LoLPicData previous,
                                  boolean firstBlank,
                                  boolean lastBlank) {
        if (current == null) {
            return true;
        }
        if (current.beBlank() && !firstBlank && !lastBlank) {
            log.info("blank KDA, previous: {}", JSON.toJSONString(previous));
            return true;
        }
        if (previous.getK() < 0 || current.getK() < 0) {
            return false;
        }
        boolean invalid = current.getK() - previous.getK() > 5 || current.getK() < previous.getK()
                || current.getD() - previous.getD() > 2 || current.getD() < previous.getD()
                || current.getA() - previous.getA() > 5 || current.getA() < previous.getA();
        if (invalid) {
            log.info("invalid KDA, current: {}, previous: {}",
                    JSON.toJSONString(current), JSON.toJSONString(previous));
        }
        return invalid;
    }

    private LoLPicData copyPicData(LoLPicData source) {
        LoLPicData copy = new LoLPicData(source.getK(), source.getD(), source.getA());
        copy.setHeroKADetail(source.getHeroKADetail());
        return copy;
    }

    /**
     * 将相邻 KDA 点转换为事件；击杀、助攻、死亡增量与击杀栏详情共同决定分数。
     */
    private HighlightEvent createEvent(LolKdaTimelinePoint previousPoint,
                                       LolKdaTimelinePoint currentPoint) {
        LoLPicData previous = previousPoint.getPicData();
        LoLPicData current = currentPoint.getPicData();
        if (!previous.beValid() || !current.beValid()) {
            return null;
        }

        int deltaKill = Math.max(0, current.getK() - previous.getK());
        int deltaDeath = Math.max(0, current.getD() - previous.getD());
        int deltaAssist = Math.max(0, current.getA() - previous.getA());
        float score = calculateScore(previous, current);
        if (score == 0f && deltaKill == 0 && deltaDeath == 0 && deltaAssist == 0) {
            return null;
        }

        Map<String, Object> evidence = new HashMap<>();
        evidence.put("kda", current.getK() + "/" + current.getD() + "/" + current.getA());
        evidence.put("deltaKill", deltaKill);
        evidence.put("deltaDeath", deltaDeath);
        evidence.put("deltaAssist", deltaAssist);
        return HighlightEvent.builder()
                .sourceVideo(currentPoint.getSourceVideo())
                .secondFromVideoStart(currentPoint.getSecondFromVideoStart())
                .eventType(eventType(deltaKill, deltaDeath, deltaAssist))
                .score(score)
                .positiveCount(deltaKill + deltaAssist)
                .negativeCount(deltaDeath)
                .confidence(1.0f)
                .evidence(evidence)
                .build();
    }

    /**
     * 基础分：击杀 +6、助攻 +2、死亡 -3；一次增加多个击杀时每个额外击杀 +2。
     * 击杀栏详情最多再加 10 分，参与人数越少分数越高。
     */
    private float calculateScore(LoLPicData previous, LoLPicData current) {
        if (!previous.beValid() || !current.beValid()) {
            return 0f;
        }
        if (current.getA() <= previous.getA() && current.getK() <= previous.getK()) {
            return 0f;
        }
        int deltaKill = current.getK() - previous.getK();
        int deltaDeath = current.getD() - previous.getD();
        int deltaAssist = current.getA() - previous.getA();
        float kdaGain = KILL_GAIN * deltaKill
                + ASSIST_GAIN * deltaAssist
                - DEATH_PENALTY * deltaDeath;
        float comboGain = EXTRA_KILL_COMBO_GAIN * Math.max(deltaKill - 1, 0);
        float detailGain = Math.min(calculateDetailGain(current), MAX_DETAIL_GAIN);
        return kdaGain + comboGain + detailGain;
    }

    private float calculateDetailGain(LoLPicData current) {
        float gain = 0f;
        for (List<Integer> sameLine : current.merge2PositionEnum()) {
            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_KILL.getLabelId())) {
                if (sameLine.size() == 2) {
                    gain += 8.0f;
                } else {
                    gain += 6.0f / Math.max(sameLine.size() - 1, 1);
                }
            }
            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_ASSIST.getLabelId())) {
                gain += 4.0f / Math.max(sameLine.size() - 1, 1);
            }
        }
        return gain;
    }

    private String eventType(int deltaKill, int deltaDeath, int deltaAssist) {
        if (deltaKill > 0) {
            return SELF_KILL_EVENT;
        }
        if (deltaAssist > 0) {
            return "SELF_ASSIST";
        }
        if (deltaDeath > 0) {
            return "SELF_DEATH";
        }
        return "KDA_CHANGE";
    }
}
