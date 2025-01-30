package com.sh.engine.service.process;

import java.io.File;
import java.util.List;

public interface VideoMergeService {
    boolean concatByDemuxer(List<String> mergedFileNames, File targetVideo);

    boolean concatByProtocol(List<String> mergedFileNames, File targetVideo);

    /**
     * 采用concate protool进行拼接
     *
     * @param intervals
     * @param targetVideo
     * @return
     */
    boolean mergeMultiWithFade(List<List<String>> intervals, File targetVideo);

    /**
     * 采用concate filter进行拼接
     *
     * @param intervals
     * @param targetVideo
     * @param title
     * @return
     */
    boolean mergeMultiWithFadeV2(List<List<String>> intervals, File targetVideo, String title);
}
