package com.sh.engine.processor.plugin;

import com.sh.config.model.storage.FileStatusModel;
import com.sh.config.utils.EnvUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.AssVideoMergeCmd;
import com.sh.engine.processor.recorder.danmu.AssWriter;
import com.sh.engine.processor.recorder.danmu.SimpleDanmaku;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DanmakuMergePlugin implements VideoProcessPlugin {
    @Resource
    MsgSendService msgSendService;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAMAKU_MERGE.getType();
    }

    @Override
    public boolean process(String recordPath) {
        // 查找单一弹幕文件
        File danmuFile = new File(recordPath, RecordConstant.DAMAKU_TXT_ALL_FILE);
        if (!danmuFile.exists()) {
            log.info("Danmaku file does not exist: {}", danmuFile.getAbsolutePath());
            return true;
        }

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

        // 读取所有弹幕并按时间戳排序
        List<SimpleDanmaku> allDanmakus = readDanmakuFromFile(danmuFile);
        if (CollectionUtils.isEmpty(allDanmakus)) {
            log.info("Danmaku file is empty: {}", danmuFile.getAbsolutePath());
            return true;
        }

        // 按绝对时间戳排序
        Collections.sort(allDanmakus, Comparator.comparingLong(SimpleDanmaku::getTimestamp));

        // 从FileStatusModel中获取视频元信息
        FileStatusModel fileStatusModel = FileStatusModel.loadFromFile(recordPath);
        Map<String, FileStatusModel.VideoMetaInfo> metaMap = fileStatusModel.getMetaMap();
        if (metaMap == null) {
            log.warn("Video meta info not found in FileStatusModel, skipping danmaku merge: {}", recordPath);
            return true;
        }

        // 处理每个视频文件
        for (File videoFile : videoFiles) {
            String name = videoFile.getName();
            FileStatusModel.VideoMetaInfo info = metaMap.get(name);
            if (info == null) {
                log.warn("Video meta info not found for video: {}, skipping danmaku merge", name);
                continue;
            }

            // 使用视频元信息中的时间范围
            long videoStartTimestamp = info.getRecordStartTimeStamp();
            long videoEndTimestamp = info.getRecordEndTimeStamp();

            // 使用二分查找在指定时间戳范围内查找弹幕
            List<SimpleDanmaku> videoDanmakus = findDanmakusInTimestampRange(allDanmakus, videoStartTimestamp, videoEndTimestamp);

            // 生成对应的ass文件
            String assFileName = videoFile.getName().replace(".mp4", ".ass");
            File assFile = new File(recordPath, assFileName);
            
            // 仅当ass文件不存在时才转换
            if (!assFile.exists()) {
                log.info("Generating danmaku file: {}, video duration: {}s, danmaku count: {}", 
                        assFile.getName(), info.getDurationSecond(), videoDanmakus.size());
                convertDanmakusToAssFile(videoDanmakus, assFile, info.getWidth(), info.getHeight(), info.getRecordStartTimeStamp());
            }

            // 生成带弹幕的视频
            String damakuFileName = RecordConstant.DAMAKU_FILE_PREFIX + videoFile.getName();
            File damakuFile = new File(recordPath, damakuFileName);
            
            // 仅当弹幕视频不存在时才合并
            if (!damakuFile.exists()) {
                AssVideoMergeCmd assVideoMergeCmd = new AssVideoMergeCmd(assFile, videoFile);
                assVideoMergeCmd.execute(10 * 3600);
                if (assVideoMergeCmd.isNormalExit()) {
                    msgSendService.sendText("Merging danmaku file success! Path: " + damakuFile.getAbsolutePath());
                    // 成功则删除原始MP4文件
                    if (EnvUtil.isProd()) {
                        FileUtils.deleteQuietly(videoFile);
                    }
                } else {
                    msgSendService.sendText("Merging danmaku file failed! Path: " + damakuFile.getAbsolutePath());
                    // 失败则删除合并的弹幕文件
                    if (EnvUtil.isProd()) {
                        FileUtils.deleteQuietly(damakuFile);
                    }
                }
            }
        }

        return true;
    }

    @Override
    public int getMaxProcessParallel() {
        return 1;
    }

    /**
     * 从文件读取弹幕列表
     * @param txtFile 弹幕文件
     * @return 弹幕列表
     */
    private List<SimpleDanmaku> readDanmakuFromFile(File txtFile) {
        List<SimpleDanmaku> danmakus = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(txtFile.toPath()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    SimpleDanmaku danmaku = SimpleDanmaku.fromLine(line);
                    if (danmaku != null) {
                        danmakus.add(danmaku);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error reading danmaku file, txtFile: {}", txtFile.getAbsolutePath(), e);
        }
        return danmakus;
    }

    /**
     * 使用二分查找在指定时间戳范围内查找弹幕
     * @param allDanmakus 所有已排序的弹幕（按时间戳排序）
     * @param startTimestamp 开始时间戳（秒）
     * @param endTimestamp 结束时间戳（秒）
     * @return 时间戳范围内的弹幕
     */
    private List<SimpleDanmaku> findDanmakusInTimestampRange(List<SimpleDanmaku> allDanmakus, long startTimestamp, long endTimestamp) {
        // 使用二分查找找到开始和结束索引
        int startIndex = findFirstGreaterOrEqualTimestampIndex(allDanmakus, startTimestamp);
        int endIndex = findFirstGreaterOrEqualTimestampIndex(allDanmakus, endTimestamp);

        // 提取时间戳范围内的弹幕
        List<SimpleDanmaku> result = new ArrayList<>();
        for (int i = startIndex; i < endIndex && i < allDanmakus.size(); i++) {
            SimpleDanmaku danmaku = allDanmakus.get(i);
            if (danmaku.getTimestamp() >= startTimestamp && danmaku.getTimestamp() <= endTimestamp) {
                result.add(danmaku);
            }
        }

        return result;
    }

    /**
     * 使用二分查找找到时间戳大于或等于指定值的第一个索引
     * @param danmakus 已按时间戳排序的弹幕列表
     * @param timestamp 目标时间戳
     * @return 索引位置
     */
    private int findFirstGreaterOrEqualTimestampIndex(List<SimpleDanmaku> danmakus, long timestamp) {
        int left = 0;
        int right = danmakus.size();
        
        while (left < right) {
            int mid = left + (right - left) / 2;
            if (danmakus.get(mid).getTimestamp() < timestamp) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }
        
        return left;
    }

    /**
     * 将弹幕列表转换为ass格式
     * @param danmakus 弹幕列表
     * @param assFile ass文件
     * @param width 视频宽度
     * @param height 视频高度
     */
    private void convertDanmakusToAssFile(List<SimpleDanmaku> danmakus, File assFile, int width, int height, long videoStartTimestamp) {
        AssWriter assWriter = new AssWriter("Live Danmaku", width, height);
        
        try {
            assWriter.open(assFile.getAbsolutePath());
            
            // 将所有弹幕写入ass文件
            for (SimpleDanmaku danmaku : danmakus) {
                long time = danmaku.getTimestamp() - videoStartTimestamp;
                danmaku.setTime((float) time);
                assWriter.add(danmaku);
            }
        } catch (Exception e) {
            log.error("Error converting danmaku to ass file, assFile: {}", assFile.getAbsolutePath(), e);
        } finally {
            assWriter.close();
        }
    }
}