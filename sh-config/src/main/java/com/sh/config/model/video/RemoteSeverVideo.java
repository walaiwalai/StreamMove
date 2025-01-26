package com.sh.config.model.video;

import lombok.Data;

/**
 * 已经上传到服务器的视频对象
 * @author caiWen
 * @date 2023/1/26 9:25
 */
@Data
public class RemoteSeverVideo {
    /**
     * 上传的视频在服务器上的地址
     */
    private String serverFileName;

    /**
     * 本地文件路径
     */
    private String localFilePath;

    public RemoteSeverVideo() {
    }

    public RemoteSeverVideo( String serverFileName, String localFilePath) {
        this.serverFileName = serverFileName;
        this.localFilePath = localFilePath;
    }
}
