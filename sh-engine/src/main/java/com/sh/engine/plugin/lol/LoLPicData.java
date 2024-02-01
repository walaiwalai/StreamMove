package com.sh.engine.plugin.lol;

import lombok.Data;

import java.io.File;

/**
 * @Author caiwen
 * @Date 2024 01 13 23 26
 **/
@Data
public class LoLPicData {
    private int K;
    private int D;
    private int A;

    private Integer targetIndex;

    public LoLPicData(int k, int d, int a) {
        K = k;
        D = d;
        A = a;
    }


}
