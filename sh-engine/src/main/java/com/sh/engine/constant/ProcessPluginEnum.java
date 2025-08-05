package com.sh.engine.constant;

import org.apache.commons.collections4.CollectionUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
public enum ProcessPluginEnum {
    LOL_HL_VOD_CUT("LOL_HL_VOD_CUT", "lol精彩片段剪辑", false, 1),
    BATCH_SEG_MERGE("BATCH_SEG_MERGE", "视频切片合并", true, 5),
    THUMBNAIL_GEN("THUMBNAIL_GEN", "视频封面生成", false, 15),
    ;

    final String type;
    final String desc;
    final boolean system;

    /**
     * 越小越先处理
     */
    final int order;

    ProcessPluginEnum(String type, String desc, boolean system, int order) {
        this.type = type;
        this.desc = desc;
        this.system = system;
        this.order = order;
    }

    public String getType() {
        return type;
    }

    public boolean isSystem() {
        return system;
    }

    public int getOrder() {
        return order;
    }


    public static ProcessPluginEnum of(String type) {
        for (ProcessPluginEnum value : ProcessPluginEnum.values()) {
            if (Objects.equals(value.getType(), type)) {
                return value;
            }
        }
        return null;
    }

    public static List<String> getAllPlugins(List<String> plugins) {
        List<ProcessPluginEnum> allPlugins = Arrays.stream(ProcessPluginEnum.values())
                .filter(ProcessPluginEnum::isSystem)
                .collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(plugins)) {
            for (String plugin : plugins) {
                allPlugins.add(ProcessPluginEnum.of(plugin));
            }
        }

        // 按照order排序
        return allPlugins.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ProcessPluginEnum::getOrder))
                .map(ProcessPluginEnum::getType)
                .collect(Collectors.toList());
    }
}
