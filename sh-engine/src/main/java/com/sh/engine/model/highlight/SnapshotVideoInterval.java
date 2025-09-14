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

    public SnapshotVideoInterval(File fromVideo, double secondFromVideoStart, double secondToVideoEnd, float score) {
        super(fromVideo, secondFromVideoStart, secondToVideoEnd);
        this.score = score;
    }

    public float getScore() {
        return score;
    }

    public SnapshotVideoInterval copy() {
        return new SnapshotVideoInterval(this.getFromVideo(), this.getSecondFromVideoStart(), this.getSecondToVideoEnd(), this.score);
    }

    /**
     * 合并当前区间与另一个区间
     * 前提：两个区间属于同一个视频文件（fromVideo相同）
     *
     * @param other 要合并的另一个区间
     * @return 合并后的新区间，分数为两个区间分数之和
     * @throws IllegalArgumentException 如果两个区间不属于同一个视频，抛出异常
     */
    public SnapshotVideoInterval merge(SnapshotVideoInterval other) {
        // 校验是否为同一个视频文件
        if (!this.getFromVideo().equals(other.getFromVideo())) {
            throw new IllegalArgumentException("只能合并同一视频文件的区间");
        }

        // 创建合并后的区间，分数为两个区间分数之和
        return new SnapshotVideoInterval(
                this.getFromVideo(),
                Math.min(this.getSecondFromVideoStart(), other.getSecondFromVideoStart()),
                Math.max(this.getSecondToVideoEnd(), other.getSecondToVideoEnd()),
                this.score + other.getScore()
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
