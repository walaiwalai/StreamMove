package com.sh.engine.processor.plugin;

import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.processor.plugin.highlight.valorant.analysis.ValorantHighlightAnalyzer;
import com.sh.engine.model.highlight.core.HighlightProcessContext;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.ScoredVideoInterval;
import java.util.List;
import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightOutputMode;
import com.sh.engine.processor.plugin.highlight.AbstractHighlightCutPlugin;
import com.sh.engine.processor.plugin.highlight.HighlightTimelineProvider;
import com.sh.engine.processor.plugin.highlight.valorant.ValorantHighlightTimelineProvider;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 无畏契约高光插件：基于事件时间线生成高光区间，并输出带封面混剪视频。
 */
@Component
public class ValorantVodHighLightCutPlugin extends AbstractHighlightCutPlugin {
    @Resource
    private ValorantHighlightTimelineProvider timelineProvider;

    @Resource
    private ValorantHighlightAnalyzer analyzer;

    @Override
    protected List<ScoredVideoInterval> selectIntervals(HighlightProcessContext context, List<HighlightEvent> timeline) {
        return analyzer.isEnabled() ? analyzer.analyze(context, timeline) : super.selectIntervals(context, timeline);
    }

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.VALORANT_HL_VOD_CUT.getType();
    }

    @Override
    protected HighlightTimelineProvider timelineProvider() {
        return timelineProvider;
    }

    @Override
    protected HighlightCutPolicy cutPolicy() {
        return HighlightCutPolicy.builder()
                .clusterGapSeconds(8)
                .preRollSeconds(7)
                .postRollSeconds(6)
                .minScore(2.0f)
                .minPositiveCount(1)
                .topN(10)
                .build();
    }

    @Override
    protected HighlightOutputMode outputMode() {
        return HighlightOutputMode.MERGED_WITH_COVER;
    }
}
