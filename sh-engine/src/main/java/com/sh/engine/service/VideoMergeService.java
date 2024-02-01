package com.sh.engine.service;

import java.io.File;
import java.util.List;

public interface VideoMergeService {
    boolean mergeVideos(List<String> mergedFileNames, File targetVideo);
}
