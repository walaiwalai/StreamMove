package com.sh.engine.model.upload;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 26
 **/
public abstract class Uploader {

    /**
     * 针对一个状态文件fileStatus.json下的视频目录
     */
    protected String uploadedDir;

    /**
     * 上传视频的元数据
     */
    protected String metaDataDir;

    public Uploader(String uploadedDir, String metaDataDir) {
        this.uploadedDir = uploadedDir;
        this.metaDataDir = metaDataDir;
    }

    /**
     * 初始化上传器
     * 如：用户cookies获取
     */
    public abstract void setUp();

    /**
     * 上传
     */
    public abstract void doUpload() throws Exception;
}
