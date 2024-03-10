package com.sh.engine.processor;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.manager.ConfigFetcher;
import com.sh.config.model.stauts.FileStatusModel;
import com.sh.config.model.video.LocalVideo;
import com.sh.config.model.video.SucceedUploadSeverVideo;
import com.sh.config.model.video.UploadVideoPair;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.RecordStageEnum;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.manager.StatusManager;
import com.sh.engine.model.RecordContext;
import com.sh.engine.model.RecordTaskStateEnum;
import com.sh.engine.upload.AbstractWorkUploadService;
import com.sh.engine.util.RecordConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2023 12 18 23 35
 **/
@Component
@Slf4j
public class VideoUploadProcessor extends AbstractRecordTaskProcessor {
    @Resource
    StatusManager statusManager;
    @Resource
    ApplicationContext applicationContext;

    Map<String, AbstractWorkUploadService> uploadServiceMap = Maps.newHashMap();

    @PostConstruct
    private void init() {
        Map<String, AbstractWorkUploadService> beansOfType = applicationContext.getBeansOfType(AbstractWorkUploadService.class);
        beansOfType.forEach((key, value) -> uploadServiceMap.put(value.getName(), value));
    }

    @Override
    public void processInternal(RecordContext context) {
        for (String curRecordPath : StreamerInfoHolder.getCurRecordPaths()) {
            FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(curRecordPath);
            if (checkNeedDoUpload(fileStatusModel)) {
                String path = fileStatusModel.getPath();
                log.info("begin upload, dirName: {}", path);

                // 1. 锁住上传视频
                statusManager.lockRecordForSubmission(path);

                try {
                    // 2.上传视频
                    upload(fileStatusModel);
                } catch (Exception e) {
                    log.error("upload video fail, dirName: {}", path, e);
                } finally {
                    // 3. 上传完成或报错解除占用
                    statusManager.releaseRecordForSubmission(path);
                }
            }
        }
    }

    private boolean checkNeedDoUpload(FileStatusModel fileStatus) {
        if (fileStatus == null) {
            return false;
        }
        String recordSavePath = fileStatus.getPath();
        if (BooleanUtils.isTrue(fileStatus.allPost())) {
            // 已经上传的
            log.info("videos in {} already posted, skip", recordSavePath);
            return false;
        }
        if (statusManager.isPathOccupied(fileStatus.getPath())) {
            return false;
        }

        // todo 限制最大上传个数
        return true;
    }

    private void upload(FileStatusModel fileStatus) {
        List<String> platforms = ConfigFetcher.getStreamerInfoByName(StreamerInfoHolder.getCurStreamerName()).getUploadPlatforms();
        for (String platform : platforms) {
            AbstractWorkUploadService service = uploadServiceMap.get(platform);
            if (service == null) {
                log.info("no available platform for uploading, will skip, platform: {}", platform);
                continue;
            }
            boolean isPost = fileStatus.fetchPostByPlatform(platform);
            if (isPost) {
                log.info("video has been uploaded, will skip, platform: {}", platform);
                continue;
            }

            // 2.需要上传的地址
            List<LocalVideo> localVideoParts = fetchLocalVideos(fileStatus, platform);
            try {
                service.upload(localVideoParts, RecordConverter.initTask(fileStatus, platform));
            } catch (Exception e) {
                log.error("upload error, platform: {}", platform, e);
            }
        }
    }


    private List<LocalVideo> fetchLocalVideos(FileStatusModel fileStatusModel, String platform) {
        String dirName = fileStatusModel.getPath();
        long videoPartLimitSize = ConfigFetcher.getInitConfig().getVideoPartLimitSize() * 1024L * 1024L;

        // 遍历本地的视频文件
        Collection<File> files = FileUtils.listFiles(new File(dirName), FileFilterUtils.suffixFileFilter("mp4"), null);
        List<File> sortedFiles = VideoFileUtils.getFileSort(Lists.newArrayList(files));
        List<String> succeedPaths = Optional.ofNullable(fileStatusModel.fetchVideoPartByPlatform(platform))
                .map(UploadVideoPair::getSucceedUploadedVideos)
                .orElse(Lists.newArrayList())
                .stream()
                .map(SucceedUploadSeverVideo::getLocalFileFullPath)
                .collect(Collectors.toList());

        List<LocalVideo> localVideos = Lists.newArrayList();
        for (File subVideoFile : sortedFiles) {
            String fullPath = subVideoFile.getAbsolutePath();
            String fileName = FileNameUtil.getPrefix(subVideoFile);
            // 过小的视频文件不上场
            long fileSize = FileUtil.size(subVideoFile);
            if (fileSize < videoPartLimitSize) {
                log.info("video size too small, give up upload, fileName: {}, size: {}, limitSize: {}",
                        subVideoFile.getName(), fileSize, videoPartLimitSize);
                continue;
            }

            // 已经上传的文件不重复上传
            LocalVideo localVideo = LocalVideo.builder()
                    .isUpload(succeedPaths.contains(fullPath))
                    .localFileFullPath(fullPath)
                    .title(fileName)
                    .fileSize(fileSize)
                    .build();
            localVideos.add(localVideo);
        }
        log.info("Final videoParts: {}", JSON.toJSONString(localVideos));
        return localVideos;
    }


    @Override
    public RecordTaskStateEnum acceptState() {
        return RecordTaskStateEnum.VIDEO_PROCESS_FINISH;
    }


    @Override
    public RecordStageEnum getStage() {
        return RecordStageEnum.VIDEO_UPLOAD;
    }

    @Override
    public RecordTaskStateEnum targetState() {
        return RecordTaskStateEnum.VIDEO_UPLOAD_FINISH;
    }
}
