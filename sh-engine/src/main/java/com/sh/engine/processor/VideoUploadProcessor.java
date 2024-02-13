package com.sh.engine.processor;

import com.alibaba.fastjson.JSON;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.service.VideoUploadService;
import com.sh.engine.util.RecordConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.NameFileFilter;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 35
 **/
@Component
@Slf4j
public class VideoUploadProcessor extends AbstractRecordTaskProcessor{
    @Resource
    private VideoUploadService videoUploadService;
    @Resource
    StatusManager statusManager;

    @Override
    public void processInternal(RecordContext context) {
        for (String curRecordPath : StreamerInfoHolder.getCurRecordPaths()) {
            FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
            if (checkNeedDoUpload(fileStatusModel)) {
                String path = fileStatusModel.getPath();
                log.info("begin upload, dirName: {}", path);

                // 1. 锁住上传视频
                statusManager.lockRecordForSubmission(path);

                try {
                    // 2.上传视频
                    videoUploadService.upload(RecordConverter.initUploadModel(fileStatusModel));
                } catch (Exception e) {
                    log.error("upload video fail, dirName: {}", path, e);
                } finally {
                    // 3. 上传完成或报错解除占用
                    statusManager.releaseRecordForSubmission(path);
                }
            }
        }
    }

    private boolean checkNeedDoUpload(FileStatusModel fileStatus) {
        if (fileStatus == null) {
            return false;
        }
        String recordSavePath = fileStatus.getPath();
        if (BooleanUtils.isTrue(fileStatus.getIsPost())) {
            // 已经上传的
            log.info("videos in {} already posted, skip", recordSavePath);
            return false;
        }
        if (statusManager.isPathOccupied(fileStatus.getPath())) {
            return false;
        }

        // todo 限制最大上传个数
        return true;
    }

    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.VIDEO_PROCESS_FINISH;
    }


    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.VIDEO_UPLOAD;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.VIDEO_UPLOAD_FINISH;
    }
}
