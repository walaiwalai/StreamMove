package com.sh.engine.constant;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Objects;
import java.util.Set;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
public enum ProcessPluginEnum {
    META_DATA_GEN("META_DATA_GEN", "视频元数据生成", true),
    THUMBNAIL_GEN("THUMBNAIL_GEN", "视频封面生成", true),
    BATCH_SEG_MERGE("BATCH_SEG_MERGE", "视频切片合并", true),
    LOL_HL_VOD_CUT("LOL_HL_VOD_CUT", "lol精彩片段剪辑", false),
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

    public static Set<String> getSystemPlugins() {
        return Sets.newLinkedHashSet(Lists.newArrayList(
                META_DATA_GEN.getType(),
                THUMBNAIL_GEN.getType(),
                BATCH_SEG_MERGE.getType()
        ));
    }
}
