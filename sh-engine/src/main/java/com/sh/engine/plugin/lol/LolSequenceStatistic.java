package com.sh.engine.plugin.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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
        List<LoLPicData> cur = fillNull();
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
        Integer preN = 6;
        Integer postN = 1;
        List<Pair<Integer, Integer>> intervals = Lists.newArrayList();
        for (Integer index : keyIndexes) {
            intervals.add(Pair.of(Math.max(0, index - preN), Math.min(index + postN, sequences.size() - 1)));
        }

        // 3. 对潜在区间进行合并
        this.potentialIntervals = merge(intervals, scoreGains);
    }

    private List<LoLPicData> fillNull() {
        LoLPicData last = new LoLPicData(-1, -1, -1);
        last.setTargetIndex(0);

        List<LoLPicData> cur = Lists.newArrayList();
        for (int i = 0; i < sequences.size(); i++) {
            LoLPicData loLPicData = sequences.get(i);
            if (needCorrect(loLPicData, last)) {
                // 没有识别出来的数据, 填充山上一个
                log.info("fill null for {}th image, last: {}", loLPicData.getTargetIndex(), JSON.toJSONString(last));
                loLPicData = last;
                loLPicData.setTargetIndex(loLPicData.getTargetIndex());
            } else {
                // 正常数据
                last = loLPicData;
            }

            cur.add(loLPicData);
        }

        return cur;
    }

    private boolean needCorrect(LoLPicData loLPicData, LoLPicData last) {
        if (loLPicData == null || loLPicData.getK() == null) {
            return true;
        }
        if (loLPicData.getK() - last.getK() > 5 || loLPicData.getD() - last.getD() > 2 || loLPicData.getA() - last.getA() > 5) {
            log.info("invalid possile, cur: {}", JSON.toJSONString(loLPicData));
            return true;
        }
        return false;
    }

    private float calGain(LoLPicData pre, LoLPicData cur) {
        Float preScore = score(pre);
        Float curScore = score(cur);
        if (preScore >= 0f && curScore >= 0f) {
            float scoreGain = curScore - preScore;
            if (scoreGain > 0.2f) {
                return scoreGain;
            }
        }
        return 0f;
    }

    private Float score(LoLPicData kad) {
        if (kad.getK() == -1) {
            return -1f;
        }

        return (float) (kad.getK() + kad.getA()) / (Math.max(1, kad.getD()));
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
                int nextR = Math.max(merged.get(merged.size() - 1).getEnd(), r);
                float nextScoreGain = interval.getScoreIncr() / (interval.getEnd() - interval.getStart()) * (nextR - interval.getStart());
                interval.setEnd(nextR);
                interval.setScoreIncr(nextScoreGain);
            }
        }

        // 找到时长最长
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
}
