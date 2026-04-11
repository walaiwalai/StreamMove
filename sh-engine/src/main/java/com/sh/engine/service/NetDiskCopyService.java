package com.sh.engine.service;

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
    void copyFileToNetDisk(UploadPlatformEnum platform, File targetFile);
}
