package com.sh.engine.base;

import com.sh.engine.RecordStageEnum;
import com.sh.engine.StreamChannelTypeEnum;
import lombok.Data;

import java.util.List;

@Data
public class Streamer {
    private String name;

    private StreamChannelTypeEnum channel;

    /**
     * 当前streamer的录像文件，可能有多个
     */
    private List<String> recordPaths;
}
