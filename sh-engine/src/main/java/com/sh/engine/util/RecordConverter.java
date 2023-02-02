package com.sh.engine.util;
import com.google.common.collect.Lists;
import java.util.Date;
import java.util.Optional;

import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;

/**
 * @author caiWen
 * @date 2023/1/26 9:47
 */
public class RecordConverter {
    public static FileStatusModel convertToFileStatusModel(Recorder recorder) {
        RecordTask recordTask = recorder.getRecordTask();
        FileStatusModel fileStatusModel = new FileStatusModel();
        fileStatusModel.setPath(recorder.getSavePath());
        fileStatusModel.setRecorderName(recordTask.getRecorderName());
        fileStatusModel.setRecorderLink(recordTask.getStreamerInfo().getRoomUrl());
        fileStatusModel.setTags(recordTask.getStreamerInfo().getTags());
        fileStatusModel.setTid(recordTask.getStreamerInfo().getTid());
        fileStatusModel.setUploadLocalFile(recordTask.getStreamerInfo().getUploadLocalFile());
        fileStatusModel.setDeleteLocalFile(recordTask.getStreamerInfo().getDeleteLocalFile());
        fileStatusModel.setIsPost(false);
        fileStatusModel.setIsFailed(false);
        fileStatusModel.setDelayTime(recordTask.getStreamerInfo().getDelayTime() == null ? 2 :
                recordTask.getStreamerInfo().getDelayTime());
        fileStatusModel.setTemplateTitle(recordTask.getStreamerInfo().getTemplateTitle());
        fileStatusModel.setDesc(recordTask.getStreamerInfo().getDesc());
        fileStatusModel.setSource(recordTask.getStreamerInfo().getSource());
        fileStatusModel.setDynamic("");
        fileStatusModel.setCopyright(0);
        fileStatusModel.setTimeV(recordTask.getStreamerInfo().getDynamic());
        fileStatusModel.setStartRecordTime(new Date());
        return fileStatusModel;
    }

    public static RecordTask convertToRecordTask(FileStatusModel fileStatus) {
        return RecordTask.builder()
                .streamerInfo(StreamerInfo.builder()
                        .name(fileStatus.getRecorderName())
                        .uploadLocalFile(Optional.ofNullable(fileStatus.getUploadLocalFile()).orElse(true))
                        .deleteLocalFile(Optional.ofNullable(fileStatus.getDeleteLocalFile()).orElse(true))
                        .templateTitle(Optional.ofNullable(fileStatus.getTemplateTitle()).orElse(""))
                        .delayTime(Optional.ofNullable(fileStatus.getDelayTime()).orElse(2))
                        .desc(Optional.ofNullable(fileStatus.getDesc()).orElse(""))
                        .source(Optional.ofNullable(fileStatus.getSource()).orElse(""))
                        .dynamic(Optional.ofNullable(fileStatus.getDynamic()).orElse(""))
                        .copyright(Optional.ofNullable(fileStatus.getCopyright()).orElse(2))
                        .roomUrl(fileStatus.getRecorderLink())
                        .tid(fileStatus.getTid())
                        .tags(Optional.ofNullable(fileStatus.getTags()).orElse(Lists.newArrayList()))
                        .build())
                .dirName(fileStatus.getPath())
                .recorderName(fileStatus.getRecorderName())
                .timeV(fileStatus.getTimeV())
                .build();
    }
}
