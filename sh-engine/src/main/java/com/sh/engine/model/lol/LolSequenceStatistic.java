package com.sh.engine.model.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.engine.model.highlight.HlScoredInterval;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static com.sh.engine.constant.RecordConstant.*;

/**
 * @Author caiwen
 * @Date 2024 01 14 10 04
 **/
@Slf4j
public class LolSequenceStatistic {
    private List<LoLPicData> sequences;
    /**
     * 潜在的精彩区间
     */
    private List<HlScoredInterval> topNIntervals;

    public LolSequenceStatistic(List<LoLPicData> datas, Integer maxIntervalCount) {
        this.sequences = datas;
        findPotentialInterval(maxIntervalCount);
    }

    public List<HlScoredInterval> getTopNIntervals() {
        return topNIntervals;
    }

    private void findPotentialInterval(int topN) {
        // 1. 补充空值
        List<LoLPicData> cur = correctSeqBySlideWindow();
        List<LoLPicData> shifted = Lists.newArrayList(new LoLPicData(-1, -1, -1));
        shifted.addAll(cur.subList(0, cur.size() - 1));

        List<Integer> keyIndexes = Lists.newArrayList();
        List<Float> scoreGains = Lists.newArrayList();
        for (int i = 0; i < sequences.size(); i++) {
            float scoreGain = calGain(shifted.get(i), cur.get(i));
            if (scoreGain > 0f) {
                keyIndexes.add(i);
                scoreGains.add(scoreGain);
            }
        }

        // 2.找到潜在的区间, 往前找preN个，往后找postN个
        List<HlScoredInterval> intervals = Lists.newArrayList();
        for (int i = 0; i < keyIndexes.size(); i++) {
            int index = keyIndexes.get(i);
            float score = scoreGains.get(i);
            HlScoredInterval scoredInterval = new HlScoredInterval(Math.max(0, index - POTENTIAL_INTERVAL_PRE_N), Math.min(index + POTENTIAL_INTERVAL_POST_N, sequences.size() - 1), score);
            intervals.add(scoredInterval);
        }

        // 3. 对潜在区间进行合并
        this.topNIntervals = findTopIntervals(intervals, topN);
    }

    private List<LoLPicData> correctSeqBySlideWindow() {
        List<LoLPicData> corrected = Lists.newArrayList();

        LinkedList<LoLPicData> window = Lists.newLinkedList();
        int left = 0, right = 0;
        while (right < sequences.size()) {
            LoLPicData cur = sequences.get(right);
            window.add(cur);
            right++;
            correctInWindow(window);

            while (left < right && window.size() > KDA_SEQ_WINDOW_SIZE) {
                corrected.add(window.removeFirst());
                left++;
            }
        }

        corrected.addAll(window);
        return corrected;
    }

    private void correctInWindow(LinkedList<LoLPicData> window) {
        if (window.size() <= 2) {
            return;
        }

        boolean hBlank = window.getFirst().beBlank();
        boolean tBlank = window.getLast().beBlank();
        LoLPicData last = LoLPicData.genBlank();
        for (int i = 0; i < window.size(); i++) {
            LoLPicData cur = window.get(i);
            if (shouldCorrect(cur, last, hBlank, tBlank)) {
                LoLPicData modify = new LoLPicData();
                BeanUtils.copyProperties(last, modify);

                window.set(i, modify);
            }
            BeanUtils.copyProperties(window.get(i), last);
        }
    }

    private boolean shouldCorrect(LoLPicData cur, LoLPicData last, boolean hBlank, boolean tBlank) {
        if (cur == null) {
            return true;
        }

        // 窗口中存在空kda孤岛,如[(2,3,4),(-1,-1,-1), (2,3,4)]
        if (cur.beBlank() && !hBlank && !tBlank) {
            log.info("blank KDA,, prev: {}", JSON.toJSONString(last));
            return true;
        }

        // 非递增或者递增太多的kad序列，如
        // [(2,3,4),(12,3,4), (2,3,4)]
        // [(2,3,4),(1,3,4), (2,3,4)]
        if (last.getK() >= 0
                && cur.getK() >= 0
                && (
                cur.getK() - last.getK() > 5 || cur.getK() < last.getK() ||
                        cur.getD() - last.getD() > 2 || cur.getD() < last.getD() ||
                        cur.getA() - last.getA() > 5 || cur.getA() < last.getA())
        ) {
            log.info("invalid KDA, cur: {}, prev: {}", JSON.toJSONString(cur), JSON.toJSONString(last));
            return true;
        }
        return false;
    }

    private float calGain(LoLPicData pre, LoLPicData cur) {
        if (pre.beBlank()) {
            return 0f;
        }
        if (cur.getA() <= pre.getA() && cur.getK() <= pre.getK()) {
            return 0f;
        }

        // 1. 说明有击杀或者助攻
        // 1.kda数值增加分数
        int deltaK = cur.getK() - pre.getK();
        int deltaA = cur.getA() - pre.getA();
        float kadGain = (float) (3 * deltaK + deltaA);

        // 2.击杀细节分数，参与击杀人数越少越精彩（包括单杀）
        float killOrAssistGain = 0f;
        List<List<Integer>> detailPositions = cur.merge2PositionEnum();
        for (List<Integer> sameLine : detailPositions) {
            float curGain = 0f;
            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_KILL.getLabelId())) {
                // 我击杀
                if (sameLine.size() == 2) {
                    // 只有敌方被击杀和我击杀敌方，说明发生单杀
                    log.info("occur solo kill! cur: {}", JSON.toJSONString(cur));
                    curGain += 8.0f;
                } else {
                    // 减1是因为有一个被击杀的敌方
                    curGain += (float) 6.0f / Math.max(sameLine.size() - 1, 1);
                }
            }

            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_ASSIST.getLabelId())) {
                // 我助攻，减1是因为有一个被击杀的敌方
                curGain += (float) 4.0f / Math.max(sameLine.size() - 1, 1);
            }

            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_KILLED.getLabelId())) {
                // 我被击杀，有扣分,减1是因为排除我被击杀
                curGain -= 4.0f / Math.max(sameLine.size() - 1, 1);
            }


            killOrAssistGain += Math.max(curGain, 0f);
        }

        return kadGain + killOrAssistGain;
    }

    /**
     * 找出最精彩的前topN个区间
     *
     * @param allIntervals 区间
     * @return 最精彩区间
     */
    private List<HlScoredInterval> findTopIntervals(List<HlScoredInterval> allIntervals, int topN) {
        allIntervals.sort(Comparator.comparingInt(HlScoredInterval::getLeftIndex));

        List<HlScoredInterval> merged = new ArrayList<>();
        for (int i = 0; i < allIntervals.size(); ++i) {
            int l = allIntervals.get(i).getLeftIndex();
            int r = allIntervals.get(i).getRightIndex();

            if (merged.size() == 0 || merged.get(merged.size() - 1).getRightIndex() < l) {
                merged.add(new HlScoredInterval(l, r, allIntervals.get(i).getScore()));
            } else {
                HlScoredInterval interval = merged.get(merged.size() - 1);
                float score = interval.getScore() + allIntervals.get(i).getScore();
                int nextR = Math.max(interval.getRightIndex(), r);
                interval.setRightIndex(nextR);
                interval.setScore(score);
            }
        }
        log.info("merged intervals: {}", JSON.toJSONString(merged));

        // 找到分数最高的前N个
        List<HlScoredInterval> topIntervals = merged.stream()
                .sorted(Comparator.comparingInt(t -> (int) (t.getScore() * (-100f))))
                .collect(Collectors.toList());

        // 按照时间顺序排列
        return topIntervals.stream()
                .limit(topN)
                .sorted(Comparator.comparingInt(HlScoredInterval::getLeftIndex))
                .collect(Collectors.toList());
    }
}
