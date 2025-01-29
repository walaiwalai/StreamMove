package com.sh.engine.processor.uploader;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.service.netdisk.NetDiskCopyService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;

/**
 * @Author : caiwen
 * @Date: 2025/1/29
 */
@Slf4j
@Component
public class BaidunPanUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;
    @Resource
    private NetDiskCopyService netDiskCopyService;

    @Override
    public String getType() {
        return UploadPlatformEnum.BAIDU_PAN.getType();
    }

    @Override
    public void setUp() {
        // 检查一下文件
        boolean isExisted = netDiskCopyService.checkBasePathExist(UploadPlatformEnum.BAIDU_PAN);
        if (!isExisted) {
            throw new StreamerRecordException(ErrorEnum.INVALID_PARAM);
        }
        log.info("baidu pan uploader init success");
    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        Collection<File> files = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false);
        if (CollectionUtils.isEmpty(files)) {
            return true;
        }
        for (File targetFile : files) {
            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(targetFile);
            if (remoteSeverVideo != null) {
                log.info("video has been uploaded to baidu pan, file: {}", targetFile.getAbsolutePath());
                continue;
            }

            remoteSeverVideo = uploadFile(targetFile);
            if (remoteSeverVideo != null) {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传百度云盘成功！");
                saveUploadedVideo(remoteSeverVideo);
            } else {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传百度云盘失败！");
                throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
            }
        }

        // 清理上传过的视频
        clearUploadedVideos();

        return true;
    }

    private RemoteSeverVideo uploadFile(File targetFile) {
        // 1. 发起网盘copy请求
        String taskId = netDiskCopyService.copyFileToNetDisk(UploadPlatformEnum.BAIDU_PAN, targetFile);

        // 2. 死循环调用查询copy状态
        int i = 0;
        boolean isFinish = false;
        while (i++ < 10000) {
            if (netDiskCopyService.checkCopyTaskFinish(taskId)) {
                isFinish = true;
                break;
            }

            try {
                // 30秒check一次
                Thread.sleep(1000 * 30);
            } catch (InterruptedException e) {
                log.error("check task finish error", e);
            }
        }

        return isFinish ? new RemoteSeverVideo(taskId, targetFile.getAbsolutePath()) : null;
    }
}
