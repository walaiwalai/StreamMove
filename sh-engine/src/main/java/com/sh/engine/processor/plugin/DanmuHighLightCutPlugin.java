package com.sh.engine.processor.plugin;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.DamakuVideoInterval;
import com.sh.engine.model.highlight.VideoInterval;
import com.sh.engine.processor.recorder.danmu.DanmakuItem;
import com.sh.engine.service.VideoMergeService;
import com.sh.engine.util.CsvUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 采集弹幕，根据弹幕密度、内容进行高能片段筛选
 *
 * @Author caiwen
 * @Date 2025 08 03 16 28
 **/
@Component
@Slf4j
public class DanmuHighLightCutPlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private MsgSendService msgSendService;
    /**
     * 最小弹幕数量
     */
    private static final int MIN_DANMU_COUNT = 1000;
    /**
     * 4秒一个片段
     */
    private static final int INTERVAL_SECOND = 4;
    /**
     * 需要选出的最佳片段
     */
    private static final int TOP_N = 10;

    /**
     * 精彩区间前后视频端个数
     */
    public static final int POTENTIAL_INTERVAL_PRE_N = 5;
    public static final int POTENTIAL_INTERVAL_POST_N = 1;

    @Override
    public String getPluginName() {
        return ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType();
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (highlightFile.exists()) {
            log.info("highlight file already existed, will skip, path: {}", recordPath);
            return true;
        }

        // 1. 读取弹幕文件
        List<DanmakuItem> danmakuItems = CsvUtil.readCsvItems(new File(recordPath, "danmu.csv").getAbsolutePath(), DanmakuItem.class);
        danmakuItems = danmakuItems.stream().filter(item -> item != null && item.getTimestamp() != null).collect(Collectors.toList());

        // 2. 读取所有MP4文件列表
        List<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videos) || danmakuItems.size() < MIN_DANMU_COUNT) {
            log.info("empty video or few damaku, will skip, path: {}", recordPath);
            return true;
        }

        // 3. 找出弹幕分数最高的弹幕区间（视频个数 * topN）, 并往前扩展preN区间，往后扩展postN个区间
        List<DamakuVideoInterval> keyIntervals = buildKeyIntervals(recordPath, videos, danmakuItems);

        // 4. 合并这些视频区间
        List<DamakuVideoInterval> merged = mergeInterval(keyIntervals);
        log.info("find topNIntervals: {}", JSON.toJSONString(merged));

        // 5. 合并视频
        String timeStr = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2) + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        List<VideoInterval> videoIntervals = merged.stream()
                .map(interval -> (VideoInterval) interval)
                .collect(Collectors.toList());
        boolean success = videoMergeService.mergeWithCover(videoIntervals, highlightFile, title);

        // 6. 发消息
        String msgPrefix = success ? "合并视频完成！路径为：" : "合并视频失败！路径为：";
        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());

        return success;
    }

    @Override
    public int getMaxProcessParallel() {
        return 5;
    }

    /**
     * 划分4秒基础区间，找出评分最高的区间
     *
     * @param videos       视频
     * @param danmakuItems 评分最高的2*topN的视频区间
     */
    private List<DamakuVideoInterval> buildKeyIntervals(String recordPath, List<File> videos, List<DanmakuItem> danmakuItems) {
        // 1. 所有视频的长度
        Map<String, Double> videoDurationMap = videos.stream()
                .collect(Collectors.toMap(FileUtil::getPrefix, v -> {
                    VideoDurationDetectCmd cmd = new VideoDurationDetectCmd(v.getAbsolutePath());
                    cmd.execute(100);
                    return cmd.getDurationSeconds();
                }));
        long recordTime = DateUtil.covertStr2Date(new File(recordPath).getName(), DateUtil.YYYY_MM_DD_HH_MM_SS_V2).getTime();
        Map<String, Long> videoCreateTimeMap = new LinkedHashMap<>();
        for (File video : videos) {
            String prefix = FileUtil.getPrefix(video);
            videoCreateTimeMap.put(prefix, recordTime);
            recordTime += (long) (videoDurationMap.getOrDefault(prefix, 0.0) * 1000);
        }

        // 2. 初始化最小堆（容量为topN）
        int heapSize = TOP_N * videos.size();
        PriorityQueue<DamakuVideoInterval> minHeap = new PriorityQueue<>(
                heapSize,
                Comparator.comparingDouble(DamakuVideoInterval::getScore).reversed()
        );
        for (File video : videos) {
            String prefix = FileUtil.getPrefix(video);
            double duartion = videoDurationMap.get(prefix);
            long createTime = videoCreateTimeMap.get(prefix);
            int count = (int) Math.ceil(duartion / INTERVAL_SECOND);
            for (int i = 0; i < count; i++) {
                double intervalStartSec = i * INTERVAL_SECOND;
                double intervalEndSec = Math.min((i + 1) * INTERVAL_SECOND, duartion);

                long startTs = createTime + (long) (intervalStartSec * 1000);
                long endTs = createTime + (long) (intervalEndSec * 1000);

                int startIndex = findIndex(danmakuItems, startTs);
                int endIndex = findIndex(danmakuItems, endTs);
                DamakuVideoInterval interval = new DamakuVideoInterval(
                        video, intervalStartSec, intervalEndSec,
                        danmakuItems.subList(startIndex, endIndex + 1)
                );

                if (minHeap.size() < heapSize) {
                    // 堆未满，直接添加
                    minHeap.add(interval);
                } else {
                    // 堆已满，只添加比堆顶分数高的区间
                    if (interval.getScore() > minHeap.peek().getScore()) {
                        minHeap.poll();
                        minHeap.add(interval);
                    }
                }
            }
        }

        // 往前扩展preN，往后扩展postN
        List<DamakuVideoInterval> keyIntervals = new ArrayList<>(minHeap);
        for (DamakuVideoInterval keyInterval : keyIntervals) {
            String prefix = FileUtil.getPrefix(keyInterval.getFromVideo());
            Double duartion = videoDurationMap.getOrDefault(prefix, 0.0);

            double expandStart = Math.max(0.0, keyInterval.getSecondFromVideoStart() - POTENTIAL_INTERVAL_PRE_N * INTERVAL_SECOND);
            double expandEnd = Math.min(keyInterval.getSecondToVideoEnd() + POTENTIAL_INTERVAL_POST_N * INTERVAL_SECOND, duartion);

            keyInterval.setSecondFromVideoStart(expandStart);
            keyInterval.setSecondToVideoEnd(expandEnd);
        }
        return keyIntervals;
    }

    private List<DamakuVideoInterval> mergeInterval(List<DamakuVideoInterval> rawIntervals) {
        // 按照视频分块
        Map<File, List<DamakuVideoInterval>> file2ItervalMap = rawIntervals.stream()
                .collect(Collectors.groupingBy(DamakuVideoInterval::getFromVideo));

        List<DamakuVideoInterval> res = Lists.newArrayList();
        for (Map.Entry<File, List<DamakuVideoInterval>> entry : file2ItervalMap.entrySet()) {
            List<DamakuVideoInterval> intervals = entry.getValue();
            intervals.sort(Comparator.comparingDouble(DamakuVideoInterval::getSecondFromVideoStart));

            List<DamakuVideoInterval> merged = new ArrayList<>();
            for (int i = 0; i < intervals.size(); ++i) {
                double startSec = intervals.get(i).getSecondFromVideoStart();
                if (merged.size() == 0 || merged.get(merged.size() - 1).getSecondToVideoEnd() < startSec) {
                    merged.add(intervals.get(i).copy());
                } else {
                    DamakuVideoInterval interval = merged.get(merged.size() - 1);
                    merged.set(merged.size() - 1, interval.merge(intervals.get(i)));
                }
            }
            res.addAll(merged);
        }

        // 找到分数最高的前N个
        List<DamakuVideoInterval> topIntervals = res.stream()
                .sorted(Comparator.comparingInt(t -> (int) (t.getScore() * (-100f))))
                .collect(Collectors.toList());

        log.info("best interval is: {}", topIntervals.get(0));
        return topIntervals.stream()
                .limit(TOP_N)
                .sorted(Comparator.comparingDouble(DamakuVideoInterval::getSecondFromVideoStart))
                .collect(Collectors.toList());
    }

    /**
     * 二分查找第一个大于等于目标时间戳的索引
     */
    private static int findIndex(List<DanmakuItem> items, long target) {
        int low = 0;
        int high = items.size() - 1;

        while (low < high) {
            int mid = (low + high) / 2;
            if (items.get(mid).getTimestamp() < target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
