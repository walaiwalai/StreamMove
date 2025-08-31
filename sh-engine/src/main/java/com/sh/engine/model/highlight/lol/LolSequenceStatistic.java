package com.sh.engine.model.highlight.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import java.util.LinkedList;
import java.util.List;

import static com.sh.engine.constant.RecordConstant.KDA_SEQ_WINDOW_SIZE;

/**
 * @Author caiwen
 * @Date 2024 01 14 10 04
 **/
@Slf4j
public class LolSequenceStatistic {
    /**
     * 序列数据
     */
    private List<LoLPicData> sequences;

    public LolSequenceStatistic(List<LoLPicData> datas) {
        this.sequences = datas;
    }

    public void calScore() {
        fillPotentialInterval();
    }

    public List<LoLPicData> getSequences() {
        return sequences;
    }

    private void fillPotentialInterval() {
        // 1. 补充空值
        this.sequences = correctSeqBySlideWindow();
        List<LoLPicData> shifted = Lists.newArrayList(new LoLPicData(-1, -1, -1));
        shifted.addAll(this.sequences.subList(0, this.sequences.size() - 1));

        for (int i = 0; i < sequences.size(); i++) {
            float scoreGain = calGain(shifted.get(i), this.sequences.get(i));
            sequences.get(i).setScore(scoreGain);
        }
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
}
