package com.sh.engine.base;

import lombok.Data;

@Data
public class Streamer {
    private String name;
    private String recordPath;

    /**
     * 针对下载的分片总量
     */
    private Integer segCount;
}
