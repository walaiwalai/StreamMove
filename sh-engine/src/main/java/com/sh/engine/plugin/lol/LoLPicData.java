package com.sh.engine.plugin.lol;

import lombok.Data;

import java.util.Objects;

/**
 * @Author caiwen
 * @Date 2024 01 13 23 26
 **/
@Data
public class LoLPicData {
    private Integer K;
    private Integer D;
    private Integer A;
    private Integer targetIndex;

    public LoLPicData() {
    }

    public LoLPicData(Integer k, Integer d, Integer a) {
        K = k;
        D = d;
        A = a;
    }


    public static LoLPicData genBlank() {
        return new LoLPicData(-1, -1, -1);
    }

    public static LoLPicData genInvalid() {
        return new LoLPicData(-2, -2, -2);
    }

    public boolean isInvalid() {
        return this.K == -2;
    }

    public boolean isSameKda(LoLPicData other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(this.K, other.K) && Objects.equals(this.D, other.D) && Objects.equals(this.A, other.A);
    }

}
