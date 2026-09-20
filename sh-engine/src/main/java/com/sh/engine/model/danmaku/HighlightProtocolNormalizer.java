package com.sh.engine.model.danmaku;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将模型协议中的等价空值统一为 Java 空值，同时保留审计日志中的原始响应。 */
final class HighlightProtocolNormalizer {
    private static final Set<String> EMPTY_SENTINELS = new HashSet<>(Arrays.asList(
            "无", "没有", "不存在", "none", "null", "nil", "na", "notapplicable",
            "无不支持主张", "无不支持的主张", "无阻断项", "无限制"));

    private HighlightProtocolNormalizer() {
    }

    static List<String> normalizeIssueList(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (!isEmptySentinel(value)) {
                normalized.add(value.trim());
            }
        }
        return normalized;
    }

    static String normalizeOptionalText(String value) {
        return isEmptySentinel(value) ? "" : value;
    }

    private static boolean isEmptySentinel(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String canonical = value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。.;；:：!！?？\\[\\]\\\"'“”‘’/]+", "");
        return canonical.isEmpty() || EMPTY_SENTINELS.contains(canonical);
    }
}
