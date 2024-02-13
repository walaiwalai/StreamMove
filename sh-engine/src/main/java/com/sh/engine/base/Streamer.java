package com.sh.engine.base;

import lombok.Data;

import java.util.List;

@Data
public class Streamer {
    private String name;

    /**
     * 当前streamer的录像文件，可能有多个
     */
    private List<String> recordPaths;
}
