package com.sh.engine.util;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.UploadVideoPair;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.bili.BiliVideoUploadTask;
import com.sh.engine.model.record.Recorder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;

import java.util.Map;
import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/1/26 9:47
 */
public class RecordConverter {
    public static FileStatusModel convertToFileStatusModel(Recorder recorder) {
        String recorderName = StreamerInfoHolder.getCurStreamerName();

        FileStatusModel fileStatusModel = new FileStatusModel();
        fileStatusModel.setPath(recorder.getSavePath());
        fileStatusModel.setRecorderName(recorderName);
        fileStatusModel.setIsPost(false);
        fileStatusModel.setIsFailed(false);
        fileStatusModel.setTimeV(recorder.getTimeV());
        return fileStatusModel;
    }

    public static BiliVideoUploadTask initUploadModel(FileStatusModel fileStatus) {
        UploadVideoPair videoParts = fileStatus.getVideoParts();
        return BiliVideoUploadTask.builder()
                .streamerName(fileStatus.getRecorderName())
                .dirName(fileStatus.getPath())
                .title(genVideoTitle(fileStatus.getTimeV(), fileStatus.getRecorderName()))
                .succeedUploaded(Optional.ofNullable(videoParts).map(UploadVideoPair::getSucceedUploadedVideos)
                        .orElse(Lists.newArrayList()))
                .isUploadFail(fileStatus.getIsFailed())
                .failUpload(Optional.ofNullable(videoParts)
                        .map(UploadVideoPair::getFailedUploadVideo).orElse(null))
                .build();
    }

    private static String genVideoTitle(String timeV, String name) {
        Map<String, String> paramsMap = Maps.newHashMap();
        paramsMap.put("time", timeV);
        paramsMap.put("name", name);

        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(name);
        if (StringUtils.isNotBlank(streamerConfig.getTemplateTitle())) {
            StringSubstitutor sub = new StringSubstitutor(paramsMap);
            return sub.replace(streamerConfig.getTemplateTitle());
        } else {
            return name + " " + timeV + " " + "录播";
        }
    }
}
