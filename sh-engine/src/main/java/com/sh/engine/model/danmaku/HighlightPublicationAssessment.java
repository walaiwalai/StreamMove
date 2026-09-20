package com.sh.engine.model.danmaku;

import lombok.Data;

import java.util.regex.Pattern;

/**
 * 在事实验真通过后，对陌生观众观看价值和发布包装进行独立评审。
 */
@Data
public class HighlightPublicationAssessment {
    private static final int MINIMUM_TITLE_CODE_POINTS = 8;
    private static final int MAXIMUM_TITLE_CODE_POINTS = 18;
    private static final int MINIMUM_COVER_TEXT_CODE_POINTS = 4;
    private static final int MAXIMUM_COVER_TEXT_CODE_POINTS = 12;
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile("[，,。；;：:！!？?]");

    private Boolean publishable;
    private Integer score;
    private String reason;
    private String oneSentenceStory;
    private String suggestedTitle;
    private String coverText;
    private HighlightFactAlignment factAlignment;
    private HighlightPayoffAssessment payoff;
    private HighlightPublicationQuality quality;

    /** JSON 可解析但漏必填字段仍属于协议失败，不能误判为内容不通过。 */
    public boolean hasCompleteProtocol() {
        return publishable != null
                && score != null
                && reason != null
                && oneSentenceStory != null
                && suggestedTitle != null
                && coverText != null
                && factAlignment != null
                && factAlignment.hasCompleteProtocol()
                && quality != null
                && quality.hasCompleteProtocol();
    }

    /** 规范化嵌套协议值；缺失字段仍保留为 null，供完整性校验识别。 */
    public void normalizeProtocolValues() {
        if (factAlignment != null) {
            factAlignment.normalizeProtocolValues();
        }
        if (payoff != null) {
            payoff.normalizeProtocolValues();
        }
    }

    /**
     * 包装字段超长不应否决内容；只删除空白并保留原文首个完整短句或前缀，不生成新事实。
     */
    public void normalizeCopyLengths() {
        suggestedTitle = normalizeCopy(
                suggestedTitle, MINIMUM_TITLE_CODE_POINTS, MAXIMUM_TITLE_CODE_POINTS);
        coverText = normalizeCopy(
                coverText, MINIMUM_COVER_TEXT_CODE_POINTS, MAXIMUM_COVER_TEXT_CODE_POINTS);
    }

    private String normalizeCopy(String value, int minimum, int maximum) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", "").trim();
        if (codePointLength(normalized) <= maximum) {
            return normalized;
        }
        for (String clause : CLAUSE_SEPARATOR.split(normalized)) {
            int length = codePointLength(clause);
            if (length >= minimum && length <= maximum) {
                return clause;
            }
        }
        int endIndex = normalized.offsetByCodePoints(0, maximum);
        return normalized.substring(0, endIndex);
    }

    private int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }
}
