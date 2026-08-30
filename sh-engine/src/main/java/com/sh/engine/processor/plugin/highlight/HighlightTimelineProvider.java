package com.sh.engine.processor.plugin.highlight;

import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.HighlightProcessContext;

import java.util.List;

/**
 * 游戏侧唯一必须实现的主接口：把视频转换成统一的带分事件时间线。
 */
public interface HighlightTimelineProvider {
    /**
     * 从上下文构建可打分的高光事件时间线。
     *
     * @param context 高光处理上下文，包含源片段与工作目录
     * @return 已完成识别层去重和评分的高光事件；排序与区间规划由规划器统一负责
     */
    List<HighlightEvent> buildScoredTimeline(HighlightProcessContext context);
}
