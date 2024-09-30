package com.sh.engine.plugin.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
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
    private Integer maxSegs;
    /**
     * 潜在的精彩区间
     * left: 潜在区间的开始
     * right：潜在区间的结束
     */
    private List<Pair<Integer, Integer>> potentialIntervals;

    public LolSequenceStatistic(List<LoLPicData> datas, Integer maxIntervalCount) {
        this.sequences = datas;
        this.maxSegs = maxIntervalCount;
        findPotentialInterval();
    }

    public List<Pair<Integer, Integer>> getPotentialIntervals() {
        return potentialIntervals;
    }

    @Data
    @AllArgsConstructor
    static class HighLightInterval {
        private int start;
        private int end;
        private float scoreIncr;
    }


    private void findPotentialInterval() {
        // 1. 补充空值
        List<LoLPicData> cur = correctSeqBySlideWindow();
        List<LoLPicData> shifted = Lists.newArrayList(new LoLPicData(-1, -1, -1));
        shifted.addAll(cur.subList(0, cur.size() - 1));

        List<Integer> keyIndexes = Lists.newArrayList();
        List<Float> scoreGains = Lists.newArrayList();
        for (int i = 0; i < sequences.size(); i++) {
            float scoreGain = calGain(shifted.get(i), cur.get(i));
            if (scoreGain > 0f) {
                keyIndexes.add(cur.get(i).getTargetIndex());
                scoreGains.add(scoreGain);
            }
        }

        // 2.找到潜在的区间, 往前找preN个，往后找postN个
        LoLPicData lastData = sequences.get(sequences.size() - 1);
        Integer maxIndex = lastData.getTargetIndex();
        List<Pair<Integer, Integer>> intervals = Lists.newArrayList();
        for (Integer index : keyIndexes) {
            intervals.add(Pair.of(Math.max(1, index - POTENTIAL_INTERVAL_PRE_N), Math.min(index + POTENTIAL_INTERVAL_POST_N, maxIndex)));
        }

        // 3. 对潜在区间进行合并
        this.potentialIntervals = merge(intervals, scoreGains);
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
                modify.setTargetIndex(cur.getTargetIndex());

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
            if (sameLine.contains(LOLHeroPositionEnum.MYSELF_KILL.getLabelId())) {
                // 我击杀
                if (sameLine.size() == 2) {
                    // 只有敌方被击杀和我击杀敌方，说明发生单杀
                    log.info("occur solo kill! cur: {}", JSON.toJSONString(cur));
                    killOrAssistGain += 8.0f;
                } else {
                    // 减1是因为有一个被击杀的敌方
                    killOrAssistGain += (float) 6.0f / Math.max(sameLine.size() - 1, 1);
                }
            } else if (sameLine.contains(LOLHeroPositionEnum.MYSELF_ASSIST.getLabelId())) {
                // 我助攻，减1是因为有一个被击杀的敌方
                killOrAssistGain += (float) 4.0f / Math.max(sameLine.size() - 1, 1);
            }
        }

        return kadGain + killOrAssistGain;
    }

    private List<Pair<Integer, Integer>> merge(List<Pair<Integer, Integer>> intervals, List<Float> scoreGains) {
        intervals.sort(Comparator.comparingInt(Pair::getLeft));

        List<HighLightInterval> merged = new ArrayList<>();
        for (int i = 0; i < intervals.size(); ++i) {
            int l = intervals.get(i).getLeft();
            int r = intervals.get(i).getRight();

            if (merged.size() == 0 || merged.get(merged.size() - 1).getEnd() < l) {
                merged.add(new HighLightInterval(l, r, scoreGains.get(i)));
            } else {
                HighLightInterval interval = merged.get(merged.size() - 1);
                float score1 = (interval.getEnd() - interval.getStart()) * interval.getScoreIncr();
                float score2 = (r - l) * scoreGains.get(i);

                int nextR = Math.max(merged.get(merged.size() - 1).getEnd(), r);
//                float nextScoreGain = interval.getScoreIncr() / (interval.getEnd() - interval.getStart()) * (nextR - interval.getStart());
                float nextScoreGain = (score1 + score2) / (nextR - interval.getStart());
                interval.setEnd(nextR);
                interval.setScoreIncr(nextScoreGain);
            }
        }
        log.info("merged intervals: {}", JSON.toJSONString(merged));

        // 找到分数最高的前N个
        List<Pair<Integer, Integer>> targetIntervals = merged.stream()
                .sorted(Comparator.comparingInt(pair -> (int) (pair.getScoreIncr() * (-100f))))
                .map(i -> Pair.of(i.getStart(), i.getEnd()))
                .collect(Collectors.toList());

        int totalCnt = 0;
        List<Pair<Integer, Integer>> res = Lists.newArrayList();
        for (Pair<Integer, Integer> pair : targetIntervals) {
            if (totalCnt >= maxSegs) {
                break;
            }
            int curCnt = pair.getRight() - pair.getLeft() + 1;
            totalCnt += curCnt;
            res.add(pair);
        }

        // 按照时间顺序排列
        return res.stream()
                .sorted(Comparator.comparingInt(Pair::getLeft))
                .collect(Collectors.toList());
    }


    private List<Integer> listAssistCountWhenMyKill() {
        return null;
    }

    private List<Integer> listAssistCountWhenMyAssist() {
        return null;
    }
}
