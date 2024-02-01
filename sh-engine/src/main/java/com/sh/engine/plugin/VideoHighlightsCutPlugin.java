package com.sh.engine.plugin;

import com.sh.engine.plugin.base.ScreenshotPic;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2024 01 13 22 36
 **/
public interface VideoHighlightsCutPlugin {
    void doScreenshot(List<File> candidateVideos);


    /**
     * 针对切片文件，输入开始和结束片段片段
     *
     * @param orderedPics
     * @return
     */
    List<Pair<Integer, Integer>> filterTs(List<ScreenshotPic> orderedPics, Integer maxInterval);
}
