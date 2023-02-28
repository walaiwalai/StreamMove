package com.sh.upload.service;

import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.record.RecordTask;
import com.sh.engine.util.RecordConverter;
import com.sh.upload.manager.BiliVideoClientUploadManager;
import com.sh.upload.manager.BiliVideoWebUploadManager;
import com.sh.upload.model.BiliVideoUploadTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Optional;

/**
 * @author caiWen
 * @date 2023/2/1 23:31
 */
@Component
@Slf4j
public class RecordUploadServiceImpl implements RecordUploadService {
    @Resource
    StatusManager statusManager;
//    @Resource
//    BiliVideoWebUploadManager biliVideoWebUploadManager;
    @Resource
    BiliVideoClientUploadManager biliVideoClientUploadManager;

    @Override
    public void upload(FileStatusModel fileStatus) {
        String recordSavePath = fileStatus.getPath();
        if (StringUtils.isBlank(recordSavePath)) {
            return;
        }

        if (statusManager.isRoomPathFetchStream(recordSavePath)) {
            return;
        }

        if (statusManager.isRecordOnSubmission(recordSavePath)) {
            return;
        }

        if (BiliVideoClientUploadManager.isUploadPoolAllWork()) {
            return;
        }

        RecordTask recordTask = RecordConverter.convertToRecordTask(fileStatus);
        log.info("new upload task begin, recordName: {}, dirName: {}", recordTask.getRecorderName(),
                recordTask.getDirName());

        BiliVideoUploadTask biliVideoUploadTask = biliVideoClientUploadManager.convertToUploadModel(recordTask);
        biliVideoClientUploadManager.upload(biliVideoUploadTask);
    }
}
