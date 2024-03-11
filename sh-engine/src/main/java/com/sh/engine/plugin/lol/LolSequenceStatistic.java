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
    private Integer maxInterval;
    /**
     * 潜在的精彩区间
     * left: 潜在区间的开始
     * right：潜在区间的结束
     */
    private List<Pair<Integer, Integer>> potentialIntervals;

    public LolSequenceStatistic(List<LoLPicData> datas, Integer maxIntervalCount) {
        this.sequences = datas;
        this.maxInterval = maxIntervalCount;
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
            if (scoreGain > 0.2f) {
                keyIndexes.add(cur.get(i).getTargetIndex());
                scoreGains.add(scoreGain);
            }
        }

        // 2.找到潜在的区间, 往前找preN个，往后找postN个
        List<Pair<Integer, Integer>> intervals = Lists.newArrayList();
        for (Integer index : keyIndexes) {
            intervals.add(Pair.of(Math.max(0, index - POTENTIAL_INTERVAL_PRE_N), Math.min(index + POTENTIAL_INTERVAL_POST_N, sequences.size() - 1)));
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
        Float preScore = score(pre);
        Float curScore = score(cur);
        if (preScore >= 0f && curScore >= 0f) {
            return curScore - preScore;
        }
        return 0f;
    }

    private Float score(LoLPicData kad) {
        if (kad.getK() == -1) {
            return -1f;
        }

//        return (float) (kad.getK() + kad.getA()) / (Math.max(1, kad.getD()));
        return (float) (2 * kad.getK() + kad.getA());
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
                .limit(maxInterval)
                .map(i -> Pair.of(i.getStart(), i.getEnd()))
                .collect(Collectors.toList());

        // 按照时间顺序排列
        return targetIntervals.stream()
                .sorted(Comparator.comparingInt(Pair::getLeft))
                .collect(Collectors.toList());
    }

    public static void main(String[] args) {
        ArrayList<LoLPicData> data = Lists.newArrayList(
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(0, 0, 0),
                new LoLPicData(1, 0, 0),
                new LoLPicData(2, 0, 0),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(3, 1, 1),
                new LoLPicData(2, 1, 1),
                new LoLPicData(14, 2, 1),
                new LoLPicData(5, 2, 1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1),
                new LoLPicData(-1, -1, -1)
        );
        int index = 1;
        for (LoLPicData d : data) {
            d.setTargetIndex(index++);
        }

        LolSequenceStatistic statistic = new LolSequenceStatistic(data, 20);
    }
}
