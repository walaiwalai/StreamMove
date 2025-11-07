package com.sh.engine.processor.uploader;

import cn.hutool.core.io.FileUtil;
import com.google.common.collect.Lists;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.config.StreamerConfig;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.constant.UploadPlatformEnum;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.bili.BiliWebWorkPostCommand;
import com.sh.engine.model.bili.BiliWebVideoUploadCommand;
import com.sh.engine.model.video.RemoteSeverVideo;
import com.sh.engine.service.VideoMergeService;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2024 01 05 21 27
 **/
@Slf4j
@Component
public class BiliWebUploader extends Uploader {
    @Resource
    private MsgSendService msgSendService;
    @Resource
    private VideoMergeService videoMergeService;

    @Override
    public String getType() {
        return UploadPlatformEnum.BILI_WEB.getType();
    }

    @Override
    public int getMaxUploadParallel() {
        return EnvUtil.isStorageMounted() ? 2 : 10;
    }

    @Override
    public void initUploader() {

    }

    @Override
    public boolean upload(String recordPath) throws Exception {
        // 0. 获取要上传的文件
        List<File> localVideos = fetchUploadVideos(recordPath);
        if (CollectionUtils.isEmpty(localVideos)) {
            return true;
        }

        // 1. 上传视频
        List<RemoteSeverVideo> remoteVideos = Lists.newArrayList();
        for (int i = 0; i < localVideos.size(); i++) {
            File localVideo = localVideos.get(i);
            RemoteSeverVideo uploadedVideo = getUploadedVideo(localVideo);
            if (uploadedVideo != null) {
                log.info("video has been uploaded, will skip, path: {}", localVideo.getAbsolutePath());
                remoteVideos.add(uploadedVideo);
                continue;
            }

            // 1.1 上传单个视频
            BiliWebVideoUploadCommand command = new BiliWebVideoUploadCommand(localVideo);
            uploadedVideo = command.upload();

            if (uploadedVideo == null) {
                // 上传失败，发送作品为空均视为失败
                msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站失败！");
                throw new StreamerRecordException(ErrorEnum.UPLOAD_CHUNK_ERROR);
            }

            saveUploadedVideo(uploadedVideo);
            remoteVideos.add(uploadedVideo);

            // 1.2 发消息
            msgSendService.sendText(localVideo.getAbsolutePath() + "路径下的视频上传B站成功！");
        }

        // 2. 提交作品
        BiliWebWorkPostCommand finishCommand = new BiliWebWorkPostCommand(remoteVideos, recordPath);
        boolean isPostSuccess = finishCommand.postWork();
        if (!isPostSuccess) {
            throw new StreamerRecordException(ErrorEnum.POST_WORK_ERROR);
        }

        // 3. 提交成功清理一下缓存
        clearUploadedVideos();

        return true;
    }

    @Override
    public void preProcess(String recordPath) {
        // 特殊逻辑： 如果带有b站片头视频，再合成一份新的视频
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        StreamerConfig streamerConfig = ConfigFetcher.getStreamerInfoByName(streamerName);
        List<String> biliOpeningAnimations = streamerConfig.getBiliOpeningAnimations();
        File highlightTmpDir = new File(recordPath, "tmp-h");
        if (CollectionUtils.isEmpty(biliOpeningAnimations) || !highlightTmpDir.exists()) {
            return;
        }

        // 目标合成视频
        File exclusiveDir = new File(recordPath, getType());
        if (!exclusiveDir.exists()) {
            exclusiveDir.mkdirs();
        }

        File targetFile = new File(exclusiveDir, RecordConstant.HL_VIDEO);
        if (targetFile.exists()) {
            return;
        }

        // 根据streamerName的hash随机取BiliOpeningAnimations的片头
        int index = Math.abs(recordPath.hashCode() % biliOpeningAnimations.size());
        String biliOpeningAnimation = biliOpeningAnimations.get(index);
        List<String> localFps = FileUtils.listFiles(highlightTmpDir, FileFilterUtils.suffixFileFilter("ts"), null)
                .stream()
                .sorted(Comparator.comparingLong(File::lastModified))
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
        int insertIndex = 0;
        localFps.add(insertIndex, biliOpeningAnimation);


        // 合并视频片头
        boolean success = videoMergeService.concatDiffVideos(localFps, targetFile);
        String msgPrefix = success ? "合并视频片头完成！路径为：" : "合并视频片头失败！路径为：";
        msgSendService.sendText(msgPrefix + targetFile.getAbsolutePath());
    }

    /**
     * 获取要上传的视频
     *
     * @param recordPath 文件地址
     * @return 要上传的视频
     */
    private List<File> fetchUploadVideos(String recordPath) {
        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;

        // 遍历本地的视频文件
        List<File> allVideos = FileUtils.listFiles(new File(recordPath), FileFilterUtils.suffixFileFilter("mp4"), null)
                .stream()
                .sorted(Comparator.comparingLong(f -> {
                    if (f.getName().equals(RecordConstant.HL_VIDEO)) {
                        // 精彩剪辑放最前面
                        return 1L;
                    }
                    return f.lastModified();
                }))
                .filter(file -> FileUtil.size(file) >= videoPartLimitSize)
                .collect(Collectors.toList());


        // 专属文件夹(优先替换)
        File exclusiveDir = new File(recordPath, getType());
        Collection<File> exclusiveFiles;
        if (exclusiveDir.exists()) {
            exclusiveFiles = FileUtils.listFiles(exclusiveDir, FileFilterUtils.suffixFileFilter("mp4"), null);
        } else {
            exclusiveFiles = Collections.emptyList();
        }
        Map<String, File> exclusiveFileMap = exclusiveFiles.stream().collect(Collectors.toMap(File::getName, Function.identity(), (v1, v2) -> v2));
        List<File> res = Lists.newArrayList();
        for (File video : allVideos) {
            File addVideo = exclusiveFileMap.getOrDefault(video.getName(), video);
            res.add(addVideo);
            log.info("Final added video: {}", addVideo.getAbsolutePath());
        }
        return res;
    }
}
