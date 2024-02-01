package com.sh.engine.service;

import java.io.File;
import java.util.List;

public interface VideoMergeService {
    boolean merge(List<String> mergedFileNames, File targetVideo);
}
