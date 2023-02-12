package com.sh.upload.service;

import com.sh.config.model.stauts.FileStatusModel;

/**
 * @author caiWen
 * @date 2023/2/1 23:26
 */
public interface RecordUploadService {
    /**
     * 上传本地文件
     * @param fileStatus
     */
    void upload(FileStatusModel fileStatus);
}
