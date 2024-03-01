package com.sh.engine.plugin.lol;

import lombok.Data;

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


    public static LoLPicData genInvalid() {
        return new LoLPicData(-1, -1, -1);
    }


}
