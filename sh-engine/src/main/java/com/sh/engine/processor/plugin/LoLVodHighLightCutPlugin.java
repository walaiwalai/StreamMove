package com.sh.engine.processor.plugin;

import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.model.highlight.core.HighlightCutPolicy;
import com.sh.engine.model.highlight.core.HighlightEvent;
import com.sh.engine.model.highlight.core.HighlightOutputMode;
import com.sh.engine.processor.plugin.highlight.AbstractHighlightCutPlugin;
import com.sh.engine.processor.plugin.highlight.HighlightTimelineProvider;
import com.sh.engine.processor.plugin.highlight.lol.LolHighlightTimelineProvider;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 使用通用高光框架实现 LOL 录播精彩片段剪辑。
 */
@Component
public class LoLVodHighLightCutPlugin extends AbstractHighlightCutPlugin {
    @Resource
    private LolHighlightTimelineProvider timelineProvider;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.LOL_HL_VOD_CUT.getType();
    }

    @Override
    protected HighlightTimelineProvider timelineProvider() {
        return timelineProvider;
    }

    @Override
    protected HighlightCutPolicy cutPolicy() {
        return HighlightCutPolicy.builder()
                .clusterGapSeconds(28)
                .preRollSeconds(20)
                .postRollSeconds(8)
                .minScore(5.0f)
                .minPositiveCount(0)
                .topN(10)
                .clusterScorer(this::calculateClusterScore)
                .build();
    }

    @Override
    protected HighlightOutputMode outputMode() {
        return HighlightOutputMode.MERGED_VERTICAL_WITH_COVER;
    }

    /**
     * 多个击杀事件聚为同一区间时，每个后续击杀追加 2 分连杀奖励。
     */
    private float calculateClusterScore(List<HighlightEvent> events) {
        float score = 0f;
        int killEvents = 0;
        for (HighlightEvent event : events) {
            score += event.getScore();
            if (LolHighlightTimelineProvider.SELF_KILL_EVENT.equals(event.getEventType())
                    && event.getPositiveCount() > 0) {
                killEvents++;
            }
        }
        return score + Math.max(0, killEvents - 1) * 2.0f;
    }
}
