package com.sh.engine.processor.uploader;

import cn.hutool.core.io.FileUtil;
import com.microsoft.playwright.Page;
import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.repo.StreamerRepoService;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.video.RemoteSeverVideo;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * @Author caiwen
 * @Date 2024 09 28 22 26
 **/
public abstract class Uploader {
    @Resource
    private StreamerRepoService streamerRepoService;

    @Value("${sh.account-save.path}")
    private String accountSavePath;

    public abstract String getType();


    /**
     * 最大并行处理量
     */
    public abstract int getMaxUploadParallel();

    /**
     * 初始化上传器
     * 如：用户cookies获取
     */
    public abstract void initUploader();

    public void preProcess(String recordPath) {
    }

    /**
     * 上传
     *
     * @return
     */
    public abstract boolean upload(String recordPath) throws Exception;

    protected RemoteSeverVideo getUploadedVideo(String recordPath, File videoFile) {
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
        String remoteFileName = fileStatusModel.fetchRemoteVideoName(getType(), videoFile.getName());
        return StringUtils.isNotBlank(remoteFileName) ? new RemoteSeverVideo(remoteFileName, videoFile.getAbsolutePath()) : null;
    }

    protected void saveUploadedVideo(String recordPath, RemoteSeverVideo remoteSeverVideo) {
        String localFilePath = remoteSeverVideo.getLocalFilePath();

        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
        fileStatusModel.finishUpload(getType(), new File(remoteSeverVideo.getLocalFilePath()).getName(), remoteSeverVideo.getServerFileName());
        fileStatusModel.writeSelfToFile(recordPath);

        // 记录一下上传的视频流量
        long fileSize = FileUtil.size(new File(localFilePath));
        streamerRepoService.updateTrafficGB(StreamerInfoHolder.getCurStreamerName(), (float) fileSize / 1024 / 1024 / 1024);
    }

    /**
     * 获取账号保存文件
     *
     * @return 账号文件
     */
    protected File getAccoutFile() {
        return new File(accountSavePath, UploaderFactory.getAccountFileName(getType()));
    }

    /** 返回 AI 高光封面；未生成时由平台上传器继续使用首帧回退。 */
    protected File findHighlightCover(String recordPath) {
        File cover = new File(recordPath, RecordConstant.HIGHLIGHT_THUMBNAIL_FILE_NAME);
        return cover.isFile() ? cover : null;
    }

    /** 返回 AI 高光标题；文件缺失或不可读时保留原上传标题。 */
    protected String resolveHighlightTitle(String recordPath, String fallbackTitle) {
        File titleFile = new File(recordPath, RecordConstant.HIGHLIGHT_TITLE_FILE_NAME);
        if (!titleFile.isFile()) {
            return fallbackTitle;
        }
        try {
            String title = new String(Files.readAllBytes(titleFile.toPath()), StandardCharsets.UTF_8);
            return StringUtils.isBlank(title) ? fallbackTitle : title.trim();
        } catch (IOException e) {
            return fallbackTitle;
        }
    }

    protected void snapshot(Page page) {
        File sapshotFile = new File(accountSavePath, getType() + "-" + System.currentTimeMillis() + ".png");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get(sapshotFile.getAbsolutePath())).setFullPage(true));
    }
}
