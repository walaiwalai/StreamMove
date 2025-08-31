package com.sh.engine.model.highlight;

import com.sh.engine.processor.recorder.danmu.DanmakuItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕视频区间
 *
 * @Author caiwen
 * @Date 2025 08 30 12 35
 **/
public class DamakuVideoInterval extends VideoInterval {
    /**
     * 弹幕
     */
    private List<DanmakuItem> danmakuItems;

    /**
     * 评分
     */
    private float score;

    public DamakuVideoInterval(File fromVideo, double secondFromVideoStart, double secondToVideoEnd, List<DanmakuItem> danmakuItems) {
        super(fromVideo, secondFromVideoStart, secondToVideoEnd);
        this.danmakuItems = danmakuItems;
        this.score = calScore();
    }

    private float calScore() {
        // 简单就是弹幕数量
        return danmakuItems.size() * 1.0f;
    }

    public List<DanmakuItem> getDanmakuItems() {
        return danmakuItems;
    }

    public float getScore() {
        return score;
    }

    public DamakuVideoInterval copy() {
        return new DamakuVideoInterval(this.getFromVideo(), this.getSecondFromVideoStart(), this.getSecondToVideoEnd(), this.getDanmakuItems());
    }

    /**
     * 合并当前区间与另一个区间
     * 前提：两个区间属于同一个视频文件（fromVideo相同）
     *
     * @param other 要合并的另一个区间
     * @return 合并后的新区间，分数为两个区间分数之和
     * @throws IllegalArgumentException 如果两个区间不属于同一个视频，抛出异常
     */
    public DamakuVideoInterval merge(DamakuVideoInterval other) {
        // 校验是否为同一个视频文件
        if (!this.getFromVideo().equals(other.getFromVideo())) {
            throw new IllegalArgumentException("只能合并同一视频文件的区间");
        }

        // 计算合并后的时间范围（取最小开始时间和最大结束时间）
        double mergedStart = Math.min(this.getSecondFromVideoStart(), other.getSecondFromVideoStart());
        double mergedEnd = Math.max(this.getSecondToVideoEnd(), other.getSecondToVideoEnd());

        // 合并弹幕列表（去重处理，避免重复统计）
        List<DanmakuItem> mergedDanmakus = new ArrayList<>();
        mergedDanmakus.addAll(this.danmakuItems);
        mergedDanmakus.addAll(other.getDanmakuItems());

        // 创建合并后的区间，分数为两个区间分数之和
        DamakuVideoInterval mergedInterval = new DamakuVideoInterval(
                this.getFromVideo(),
                mergedStart,
                mergedEnd,
                mergedDanmakus
        );
        return mergedInterval;
    }
}
