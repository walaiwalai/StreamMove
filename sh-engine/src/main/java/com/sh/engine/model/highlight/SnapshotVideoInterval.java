package com.sh.engine.model.highlight;

import com.sh.config.utils.VideoFileUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2025 08 30 18 39
 **/
public class SnapshotVideoInterval extends VideoInterval implements Comparable<SnapshotVideoInterval> {
    /**
     * 评分
     */
    private float score;

    /**
     * 区间内发生的击杀数，用于合并连续击杀时追加连杀奖励。
     */
    private int killCount;

    public SnapshotVideoInterval(File fromVideo, double secondFromVideoStart, double secondToVideoEnd, float score) {
        this(fromVideo, secondFromVideoStart, secondToVideoEnd, score, 0);
    }

    public SnapshotVideoInterval(File fromVideo, double secondFromVideoStart, double secondToVideoEnd,
                                 float score, int killCount) {
        super(fromVideo, secondFromVideoStart, secondToVideoEnd);
        this.score = score;
        this.killCount = killCount;
    }

    public float getScore() {
        return score;
    }

    public int getKillCount() {
        return killCount;
    }

    public SnapshotVideoInterval copy() {
        return new SnapshotVideoInterval(this.getFromVideo(), this.getSecondFromVideoStart(),
                this.getSecondToVideoEnd(), this.score, this.killCount);
    }

    /**
     * 合并当前区间与另一个区间
     * 前提：两个区间属于同一个视频文件（fromVideo相同）
     *
     * @param other 要合并的另一个区间
     * @return 合并后的新区间；两个区间都包含击杀时额外增加连杀分
     * @throws IllegalArgumentException 如果两个区间不属于同一个视频，抛出异常
     */
    public SnapshotVideoInterval merge(SnapshotVideoInterval other) {
        // 校验是否为同一个视频文件
        if (!this.getFromVideo().equals(other.getFromVideo())) {
            throw new IllegalArgumentException("只能合并同一视频文件的区间");
        }

        int mergedKillCount = this.killCount + other.killCount;
        float comboGain = this.killCount > 0 && other.killCount > 0 ? 2.0f : 0.0f;
        // 两个区间都包含击杀时，形成连续击杀，额外奖励2分。
        return new SnapshotVideoInterval(
                this.getFromVideo(),
                Math.min(this.getSecondFromVideoStart(), other.getSecondFromVideoStart()),
                Math.max(this.getSecondToVideoEnd(), other.getSecondToVideoEnd()),
                this.score + other.getScore() + comboGain,
                mergedKillCount
        );
    }

    @Override
    public int compareTo(@NotNull SnapshotVideoInterval o) {
        // 比较fromVideo（使用文件的绝对路径作为比较依据）
        Integer thisVid = VideoFileUtil.getVideoIndex(this.getFromVideo());
        Integer otherVid = VideoFileUtil.getVideoIndex(o.getFromVideo());
        int fileCompare = thisVid.compareTo(otherVid);

        // 如果文件不同，直接返回文件比较结果
        if (fileCompare != 0) {
            return fileCompare;
        }

        // 如果文件相同，比较secondFromVideoStart
        double thisStart = this.getSecondFromVideoStart();
        double otherStart = o.getSecondFromVideoStart();

        // 处理double类型比较，避免精度问题
        return Double.compare(thisStart, otherStart);
    }
}
