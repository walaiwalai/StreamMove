package com.sh.engine.model.highlight.core;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public final class OcrTextDetection {
    private final String text;
    private final float score;
    private final List<Integer> boxes;

    public OcrTextDetection(String text, float score, List<Integer> boxes) {
        this.text = text;
        this.score = score;
        this.boxes = boxes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(boxes));
    }
}
