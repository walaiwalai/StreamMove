package com.sh.engine.model.highlight;

import lombok.Data;

/**
 * @Author caiwen
 * @Date 2025 08 11 00 18
 **/
@Data
public class HlScoredInterval {
    private int leftIndex;
    private int rightIndex;
    private float score;

    public HlScoredInterval(int leftIndex, int rightIndex, float score) {
        this.leftIndex = leftIndex;
        this.rightIndex = rightIndex;
        this.score = score;
    }

    public int getLeftIndex() {
        return leftIndex;
    }

    public int getRightIndex() {
        return rightIndex;
    }

    public float getScore() {
        return score;
    }
}