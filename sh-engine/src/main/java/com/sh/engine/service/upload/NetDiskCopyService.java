package com.sh.engine.service.upload;

import com.sh.engine.constant.UploadPlatformEnum;

import java.io.File;

/**
 * @Author : caiwen
 * @Date: 2025/1/29
 */
public interface NetDiskCopyService {
    /**
     * 初始化网盘服务
     *
     * @param platform
     * @return
     */
    boolean checkBasePathExist(UploadPlatformEnum platform);

    /**
     * 从本地存储拷贝到目标网盘
     *
     * @param platform
     * @param targetFile
     * @return 任务id
     */
    String copyFileToNetDisk(UploadPlatformEnum platform, File targetFile);

    /**
     * 检查拷贝任务是否完成
     *
     * @param taskId
     * @return
     */
    Integer getCopyTaskStatus(String taskId);

    /**
     * 重试拷贝任务
     *
     * @param taskId
     * @return
     */
    boolean retryCopyTask(String taskId);
}
