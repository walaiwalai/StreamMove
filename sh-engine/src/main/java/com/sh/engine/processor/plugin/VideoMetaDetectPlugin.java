package com.sh.engine.processor.plugin;

import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.ffmpeg.VideoSizeDetectCmd;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class VideoMetaDetectPlugin implements VideoProcessPlugin {

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.VIDEO_META_DETECT.getType();
    }

    @Override
    public boolean process(String recordPath) {
        // 查找对应的mp4视频文件
        List<File> videoFiles = new ArrayList<>(FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false))
                .stream()
                .filter(file -> file.getName().startsWith("P"))
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videoFiles)) {
            log.info("Video files do not exist: {}", recordPath);
            return true;
        }

        // 创建视频元信息映射
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
        Map<String, FileStatusModel.VideoMetaInfo> metaMap = fileStatusModel.getMetaMap();
        if (metaMap == null) {
            metaMap = new HashMap<>();
        }

        // 处理每个视频文件，获取元信息
        Date baseRecordTime = DateUtil.covertStr2Date((new File(recordPath)).getName(), DateUtil.YYYY_MM_DD_HH_MM_SS_V2);
        long baseTimestamp = baseRecordTime.getTime() / 1000;
        long cumulativeTime = 0;
        for (File videoFile : videoFiles) {
            String name = videoFile.getName();
            FileStatusModel.VideoMetaInfo info = metaMap.get(name);
            int videoDuration;
            if (info != null) {
                videoDuration = info.getDurationSecond();
            } else {
                // 获取当前视频的时长和尺寸信息
                VideoDurationDetectCmd videoDurationDetectCmd = new VideoDurationDetectCmd(videoFile.getAbsolutePath());
                videoDurationDetectCmd.execute(60 * 5);
                videoDuration = (int) videoDurationDetectCmd.getDurationSeconds();

                VideoSizeDetectCmd videoSizeDetectCmd = new VideoSizeDetectCmd(videoFile.getAbsolutePath());
                videoSizeDetectCmd.execute(60 * 5);

                long videoStartTimestamp = baseTimestamp + cumulativeTime;
                long videoEndTimeStamp = videoStartTimestamp + (long) videoDuration;

                // 创建视频元信息对象
                info = new FileStatusModel.VideoMetaInfo();
                info.setDurationSecond((int) videoDuration);
                info.setHeight(videoSizeDetectCmd.getHeight());
                info.setWidth(videoSizeDetectCmd.getWidth());
                info.setRecordStartTimeStamp(videoStartTimestamp);
                info.setRecordEndTimeStamp(videoEndTimeStamp);

                // 记录视频元信息
                metaMap.put(name, info);
                log.info("Detected video meta info for {}: duration={}s, resolution={}x{}",
                        name, videoDuration, videoSizeDetectCmd.getWidth(), videoSizeDetectCmd.getHeight());
            }
            cumulativeTime += videoDuration;
        }

        fileStatusModel.setMetaMap(metaMap);
        fileStatusModel.writeSelfToFile(recordPath);
        return true;
    }


    @Override
    public int getMaxProcessParallel() {
        return 2;
    }
}