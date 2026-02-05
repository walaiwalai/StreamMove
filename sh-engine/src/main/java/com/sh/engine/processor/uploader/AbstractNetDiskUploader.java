package com.sh.engine.processor.uploader;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.service.NetDiskCopyService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

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
    public void initUploader() {
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
        Collection<File> files = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false).stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(files)) {
            return true;
        }
        for (File targetFile : files) {
            RemoteSeverVideo remoteSeverVideo = getUploadedVideo(recordPath, targetFile);
            if (remoteSeverVideo != null) {
                log.info("video has been uploaded to {}, file: {}", getType(), targetFile.getAbsolutePath());
                continue;
            }

            remoteSeverVideo = uploadFile(targetFile);
            if (remoteSeverVideo != null) {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传" + uploadPlatformEnum.getType() + "云盘成功！");
                saveUploadedVideo(recordPath, remoteSeverVideo);
            } else {
                msgSendService.sendText(targetFile.getAbsolutePath() + "路径下的视频上传" + uploadPlatformEnum.getType() + "云盘失败！");
            }
        }


        return true;
    }

    private RemoteSeverVideo uploadFile(File targetFile) {
        // 1. 发起网盘copy请求
        String taskId = netDiskCopyService.copyFileToNetDisk(UploadPlatformEnum.of(getType()), targetFile);

        // 2. 死循环调用查询copy状态
        int i = 0;
        int reTryCnt = 0;
        boolean isFinish = false;
        while (i++ < 10000 && reTryCnt < 3) {
            Integer status = netDiskCopyService.getCopyTaskStatus(taskId);
            if (status == 2) {
                // 上传任务成功
                isFinish = true;
                break;
            }
            if (status == 7) {
                // 失败重新发起任务
                taskId = netDiskCopyService.copyFileToNetDisk(UploadPlatformEnum.of(getType()), targetFile);
                reTryCnt++;
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

    /**
     * 最大并行上传数量
     */
    @Override
    public int getMaxUploadParallel() {
        return 1;
    }
}
