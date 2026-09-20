package com.sh.engine.model.danmaku;

/**
 * 一条带稳定编号的一手证据。模型只引用编号，来源、时间和原文由本地目录维护。
 */
public final class HighlightEvidenceItem {
    private final String evidenceId;
    private final String source;
    private final int startSecond;
    private final int endSecond;
    private final String text;

    public HighlightEvidenceItem(
            String evidenceId,
            String source,
            int startSecond,
            int endSecond,
            String text) {
        if (evidenceId == null || evidenceId.trim().isEmpty()
                || source == null || source.trim().isEmpty()
                || startSecond < 0 || endSecond < startSecond
                || text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid highlight evidence item");
        }
        this.evidenceId = evidenceId;
        this.source = source;
        this.startSecond = startSecond;
        this.endSecond = endSecond;
        this.text = text;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public String getSource() {
        return source;
    }

    public int getStartSecond() {
        return startSecond;
    }

    public int getEndSecond() {
        return endSecond;
    }

    public String getText() {
        return text;
    }

    public boolean isInside(HighlightClipRange clip) {
        return clip != null && clip.containsInclusive(startSecond, endSecond);
    }
}
