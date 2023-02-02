package com.sh.config.model.video;

/**
 * 成功上传的视频片段
 * @author caiWen
 * @date 2023/1/26 9:29
 */
public class SucceedUploadSeverVideo extends RemoteSeverVideo {
    /**
     * 本地视频地址（全路径）
     */
    private String localFileFullPath;

    public String getLocalFileFullPath() {
        return localFileFullPath;
    }

    public void setLocalFileFullPath(String localFileFullPath) {
        this.localFileFullPath = localFileFullPath;
    }
}
