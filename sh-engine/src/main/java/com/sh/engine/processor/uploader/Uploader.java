package com.sh.engine.processor.uploader;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.TypeReference;
import com.microsoft.playwright.Page;
import com.sh.config.manager.CacheManager;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.video.RemoteSeverVideo;
import com.sh.engine.base.StreamerInfoHolder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Paths;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 26
 **/
public abstract class Uploader {
    @Resource
    private CacheManager cacheManager;
    @Value("${sh.account-save.path}")
    private String accountSavePath;

    public abstract String getType();

    /**
     * 初始化上传器
     * 如：用户cookies获取
     */
    public abstract void setUp();

    /**
     * 上传
     *
     * @return
     */
    public abstract boolean upload(String recordPath) throws Exception;

    protected RemoteSeverVideo getUploadedVideo( File videoFile ) {
        String path = videoFile.getAbsolutePath();
        String remoteFileName = cacheManager.getHash(buildFinishKey(), path, new TypeReference<String>() {
        });
        return StringUtils.isNotBlank(remoteFileName) ?
                new RemoteSeverVideo(remoteFileName, path) : null;
    }

    protected void saveUploadedVideo( RemoteSeverVideo remoteSeverVideo ) {
        cacheManager.setHash(buildFinishKey(), remoteSeverVideo.getLocalFilePath(), remoteSeverVideo.getServerFileName());
    }

    protected void clearUploadedVideos() {
        cacheManager.delete(buildFinishKey());
    }

    /**
     * 获取账号保存文件
     * @return  账号文件
     */
    protected File getAccoutFile() {
        return new File(accountSavePath, UploaderFactory.getAccountFileName(getType()));
    }

    protected void snapshot(Page page) {
        File sapshotFile = new File(accountSavePath, getType() + "-" + System.currentTimeMillis() + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(sapshotFile.getAbsolutePath())).setFullPage(true));
    }

    private String buildFinishKey() {
        return StreamerInfoHolder.getCurStreamerName() + "_" + getType() + "_uploaded_videos";
    }
}
