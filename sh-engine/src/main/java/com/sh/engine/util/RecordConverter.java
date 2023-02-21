package com.sh.engine.util;
import cn.hutool.extra.spring.SpringUtil;
import com.google.common.collect.Lists;
import java.util.Date;
import java.util.Optional;

import com.sh.config.manager.ConfigManager;
import com.sh.config.model.config.StreamerInfo;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.model.record.Recorder;
import org.springframework.stereotype.Component;

/**
 * @author caiWen
 * @date 2023/1/26 9:47
 */
public class RecordConverter {
    public static ConfigManager configManager = SpringUtil.getBean(ConfigManager.class);
    
    
    public static FileStatusModel convertToFileStatusModel(Recorder recorder) {
        String recorderName = recorder.getRecordTask().getRecorderName();
        StreamerInfo streamerInfo = configManager.getStreamerInfoByName(recorderName);
        
        FileStatusModel fileStatusModel = new FileStatusModel();
        fileStatusModel.setPath(recorder.getSavePath());
        fileStatusModel.setRecorderName(recorderName);
        fileStatusModel.setRecorderLink(streamerInfo.getRoomUrl());
        fileStatusModel.setTags(streamerInfo.getTags());
        fileStatusModel.setTid(streamerInfo.getTid());
        fileStatusModel.setUploadLocalFile(streamerInfo.getUploadLocalFile());
        fileStatusModel.setDeleteLocalFile(streamerInfo.getDeleteLocalFile());
        fileStatusModel.setIsPost(false);
        fileStatusModel.setIsFailed(false);
        fileStatusModel.setDelayTime(streamerInfo.getDelayTime() == null ? 2 : streamerInfo.getDelayTime());
        fileStatusModel.setTemplateTitle(streamerInfo.getTemplateTitle());
        fileStatusModel.setDesc(streamerInfo.getDesc());
        fileStatusModel.setSource(streamerInfo.getSource());
        fileStatusModel.setDynamic("");
        fileStatusModel.setCopyright(0);
        fileStatusModel.setTimeV(recorder.getRecordTask().getTimeV());
        fileStatusModel.setStartRecordTime(new Date());
        return fileStatusModel;
    }

    public static RecordTask convertToRecordTask(FileStatusModel fileStatus) {
        return RecordTask.builder()
                .dirName(fileStatus.getPath())
                .recorderName(fileStatus.getRecorderName())
                .timeV(fileStatus.getTimeV())
                .build();
    }
}
