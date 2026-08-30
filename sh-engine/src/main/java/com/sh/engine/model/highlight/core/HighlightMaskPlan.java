package com.sh.engine.model.highlight.core;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一场直播精彩剪辑共用的广告蒙层集合。
 */
@Getter
public final class HighlightMaskPlan {
    private static final HighlightMaskPlan EMPTY = new HighlightMaskPlan(Collections.emptyList());

    private final List<HighlightMask> masks;

    /**
     * 创建不可变蒙层计划，空值按无蒙层处理，集合内不允许空元素。
     */
    public HighlightMaskPlan(List<HighlightMask> masks) {
        if (masks == null || masks.isEmpty()) {
            this.masks = Collections.emptyList();
            return;
        }
        for (HighlightMask mask : masks) {
            if (mask == null) {
                throw new IllegalArgumentException("highlight mask plan must not contain null");
            }
        }
        this.masks = Collections.unmodifiableList(new ArrayList<>(masks));
    }

    /**
     * 返回不包含任何蒙层的共享计划。
     */
    public static HighlightMaskPlan empty() {
        return EMPTY;
    }

    /**
     * 判断当前计划是否不需要修改视频画面。
     */
    public boolean isEmpty() {
        return masks.isEmpty();
    }
}
