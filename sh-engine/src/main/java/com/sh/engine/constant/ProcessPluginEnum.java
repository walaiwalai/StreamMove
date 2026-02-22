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
    TS_2_MP4_TRANSFER("TS_2_MP4_TRANSFER", "ts文件转成MP4", true, 4),
    VIDEO_META_DETECT("VIDEO_META_DETECT", "视频元数据检测", true, 5),
    DAMAKU_MERGE("DAMAKU_MERGE", "弹幕合成", false, 6),
    DAN_MU_HL_VOD_CUT("DAN_MU_HL_VOD_CUT", "弹幕AI高光剪辑", false, 7),
    LOL_HL_VOD_CUT_V2("LOL_HL_VOD_CUT_V2", "lol精彩片段剪辑", false, 8),

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
                ProcessPluginEnum pluginEnum = ProcessPluginEnum.of(plugin);
                if (pluginEnum == null) {
                    continue;
                }
                allPlugins.add(pluginEnum);
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
