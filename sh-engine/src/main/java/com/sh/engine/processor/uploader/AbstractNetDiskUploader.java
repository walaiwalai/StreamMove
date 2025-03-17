package com.sh.engine.processor.uploader;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.service.upload.NetDiskCopyService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;

/**
 * @Author caiwen
 * @Date 2025 03 16 16 45
 **/
@Slf4j
public abstract class AbstractNetDiskUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;
    @Resource
    private NetDiskCopyService netDiskCopyService;

    @Override
    public void setUp() {
        // 检查一下文件
        boolean isExisted = netDiskCopyService.checkBasePathExist(UploadPlatformEnum.of(getType()));
        if (!isExisted) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }
        log.info(getType() + " uploader init success");
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        UploadPlatformEnum uploadPlatformEnum = UploadPlatformEnum.of(getType());
        Collection<File> files = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false);
        if (CollectionUtils.isEmpty(files)) {
            return true;
        }
        for (File targetFile : files) {
            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(targetFile);
            if (remoteSeverVideo != null) {
                log.info("video has been uploaded to uc pan, file: {}", targetFile.getAbsolutePath());
                continue;
            }

            remoteSeverVideo = uploadFile(targetFile);
            if (remoteSeverVideo != null) {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传" + uploadPlatformEnum.getType() + "云盘成功！");
                saveUploadedVideo(remoteSeverVideo);
            } else {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传" + uploadPlatformEnum.getType() + "云盘失败！");
                throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
            }
        }

        // 清理上传过的视频
        clearUploadedVideos();

        return true;
    }

    private RemoteSeverVideo uploadFile(File targetFile) {
        // 1. 发起网盘copy请求
        String taskId = netDiskCopyService.copyFileToNetDisk(UploadPlatformEnum.of(getType()), targetFile);

        // 2. 死循环调用查询copy状态
        int i = 0;
        boolean isFinish = false;
        while (i++ < 10000) {
            if (netDiskCopyService.checkCopyTaskFinish(taskId)) {
                isFinish = true;
                break;
            }

            try {
                // 10秒check一次
                Thread.sleep(1000 * 10);
            } catch (InterruptedException e) {
                log.error("check task finish error", e);
            }
        }

        return isFinish ? new RemoteSeverVideo(taskId, targetFile.getAbsolutePath()) : null;
    }
}
