package com.sh.engine.model.highlight.core;

import lombok.Getter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次高光剪辑任务的输入上下文。
 */
@Getter
public final class HighlightProcessContext {
    private final String recordPath;
    private final List<File> sourceVideos;
    private final File workDirectory;

    public HighlightProcessContext(String recordPath,
                                   List<File> sourceVideos,
                                   File workDirectory) {
        if (recordPath == null || recordPath.trim().isEmpty()) {
            throw new IllegalArgumentException("recordPath must not be blank");
        }
        if (sourceVideos == null || sourceVideos.isEmpty()) {
            throw new IllegalArgumentException("sourceVideos must not be empty");
        }
        if (workDirectory == null) {
            throw new IllegalArgumentException("workDirectory must not be null");
        }
        this.recordPath = recordPath;
        this.sourceVideos = Collections.unmodifiableList(new ArrayList<>(sourceVideos));
        this.workDirectory = workDirectory;
    }
}
