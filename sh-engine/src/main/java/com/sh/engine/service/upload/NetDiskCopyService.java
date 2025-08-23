package com.sh.engine.service.upload;

import java.io.File;

/**
 * @Author : caiwen
 * @Date: 2025/1/29
 */
public interface NetDiskCopyService {
    /**
     * 检查路径是否存在
     *
     * @param path
     * @return
     */
    boolean checkPathExist(String path);

    /**
     * 从本地存储拷贝到目标网盘
     *
     * @param rootDirName
     * @param targetFile
     * @return 任务id
     */
    String copyFileToNetDisk(String rootDirName, File targetFile);

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
