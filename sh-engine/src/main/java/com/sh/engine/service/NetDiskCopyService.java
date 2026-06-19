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

    /**
     * 重命名网盘上已上传文件的后缀
     *
     * @param platform 目标网盘
     * @param targetFile 上传时使用的本地文件（用于推算网盘上的远端路径与原文件名）
     * @param newSuffix 新后缀（不含点，例如 "zip"）
     */
    void renameFileSuffix(UploadPlatformEnum platform, File targetFile, String newSuffix);
}
