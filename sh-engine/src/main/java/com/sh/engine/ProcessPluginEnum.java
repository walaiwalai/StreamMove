package com.sh.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
public enum ProcessPluginEnum {
    BATCH_SEG_MERGE("BATCH_SEG_MERGE", "视频切片合并", false),
    LOL_HL_VOD_CUT("LOL_HL_VOD_CUT", "lol精彩片段剪辑", false),
    META_DATA_GEN("META_DATA_GEN", "视频元数据生成", true),
    ;

    final String type;
    final String desc;
    final boolean system;

    ProcessPluginEnum(String type, String desc, boolean system) {
        this.type = type;
        this.desc = desc;
        this.system = system;
    }

    public String getType() {
        return type;
    }

    public boolean isSystem() {
        return system;
    }

    public static ProcessPluginEnum of(String type) {
        for (ProcessPluginEnum value : ProcessPluginEnum.values()) {
            if (Objects.equals(value.getType(), type)) {
                return value;
            }
        }
        return null;
    }
}
