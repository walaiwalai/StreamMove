package com.sh.engine.model.highlight;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author caiwen
 * @Date 2025 08 11 00 18
 **/
@Data
@AllArgsConstructor
public class HlScoredInterval {
    private int start;
    private int end;
    private float score;

    /**
     * 检查当前区间是否与另一个区间重叠
     *
     * @param other 其他区间
     */
    public boolean overlapsWith(HlScoredInterval other) {
        return this.start <= other.end && other.start <= this.end;
    }

    // 合并两个重叠区间
    public HlScoredInterval mergeWith(HlScoredInterval other) {
        int newStart = Math.min(this.start, other.start);
        int newEnd = Math.max(this.end, other.end);
        // 合并后的分数可以取两个区间的最大值或平均值，这里选择最大值
        float newScore = Math.max(this.score, other.score);
        return new HlScoredInterval(newStart, newEnd, newScore);
    }

    // getter方法
    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public float getScore() {
        return score;
    }
}