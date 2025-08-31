package com.sh.engine.processor;

import cn.hutool.core.io.FileUtil;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.manager.StatusManager;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.engine.constant.RecordStageEnum;
import com.sh.engine.constant.RecordTaskStateEnum;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.processor.uploader.Uploader;
import com.sh.engine.processor.uploader.UploaderFactory;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 35
 **/
@Component
@Slf4j
public class WorkUploadStageProcessor extends AbstractStageProcessor {
    @Resource
    StatusManager statusManager;
    @Resource
    MsgSendService msgSendService;

    @Override
    public void processInternal(RecordContext context) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        if (CollectionUtils.isEmpty(streamerConfig.getUploadPlatforms())) {
            return;
        }

        for (String curRecordPath : StreamerInfoHolder.getCurRecordPaths()) {
            if (!FileUtil.exist(curRecordPath)) {
                log.error("{}'s record path not exist, maybe deleted, path: {}", streamerName, curRecordPath);
                continue;
            }
            FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
            if (statusManager.isPathOccupied(curRecordPath, streamerName)) {
                log.info("{} is doing other process, platform: {}.", streamerName, statusManager.getCurPlatform(curRecordPath));
                continue;
            }

            for (String platform : streamerConfig.getUploadPlatforms()) {
                Uploader service = UploaderFactory.getUploader(platform);
                if (service == null) {
                    log.info("no available platform for uploading, will skip, platform: {}", platform);
                    continue;
                }

                boolean isPost = fileStatusModel.isPost(platform);
                if (isPost) {
                    log.info("video has been uploaded, will skip, platform: {}", platform);
                    continue;
                }

                // 2.需要上传的地址
                statusManager.lockRecordForSubmission(curRecordPath, platform);
                boolean success = false;
                try {
                    try {
                        service.preProcess(curRecordPath);
                    } catch (Exception e) {
                        log.error("pre process error, platform: {}", platform, e);
                    }
                    success = service.upload(curRecordPath);
                } catch (Exception e) {
                    log.error("upload error, platform: {}", platform, e);
                } finally {
                    statusManager.releaseRecordForSubmission(curRecordPath);
                }

                if (success) {
                    log.info("{}'s {} platform upload success, path: {}. ", streamerName, platform, curRecordPath);
                    msgSendService.sendText(curRecordPath + "路径下的视频上传成功, 类型:" + platform);

                    // 上传平台成功状态记录
                    fileStatusModel.postSuccess(platform);
                    fileStatusModel.writeSelfToFile(curRecordPath);
                } else {
                    log.info("{}'s {} platform upload fail, path: {}. ", streamerName, platform, curRecordPath);
                    msgSendService.sendText(curRecordPath + "路径下的视频上传失败, 类型:" + platform);
                }

            }
        }
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
