//package com.sh.engine.processor.plugin;
//
//import com.google.common.collect.Lists;
//import com.sh.config.utils.VideoFileUtil;
//import com.sh.engine.base.StreamerInfoHolder;
//import com.sh.engine.constant.ProcessPluginEnum;
//import com.sh.engine.constant.RecordConstant;
//import com.sh.engine.processor.plugin.DanmuHighLightCutPlugin.DanmuSlideWindow;
//import com.sh.engine.processor.recorder.danmu.DanmakuItem;
//import com.sh.engine.service.process.VideoMergeService;
//import com.sh.engine.util.DateUtil;
//import com.sh.message.service.MsgSendService;
//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.io.FileUtils;
//import org.apache.commons.lang3.tuple.Pair;
//import org.springframework.stereotype.Component;
//
//import javax.annotation.Resource;
//import java.io.BufferedReader;
//import java.io.File;
//import java.io.FileReader;
//import java.util.*;
//import java.util.stream.Collectors;
//
///**
// * 采集弹幕，根据弹幕密度、内容进行高能片段筛选
// *
// * @Author caiwen
// * @Date 2025 08 03 16 28
// **/
//@Component
//@Slf4j
//public class DanmuHighLightCutPlugin implements VideoProcessPlugin {
//    @Resource
//    private VideoMergeService videoMergeService;
//    @Resource
//    private MsgSendService msgSendService;
//
//    // TS文件时长(秒)
//    private static final int TS_DURATION = 4;
//    // 滑动窗口大小(TS文件数量)
//    private static final int WINDOW_SIZE = 5;
//    // 需要选出的最佳窗口数量
//    private static final int TOP_N = 10;
//    // TS文件名中提取时间戳的正则表达式(根据实际文件名格式调整)
//    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("seg-(\\d+)\\.ts");
//    // 日期格式(如果TS文件名使用日期格式而非时间戳)
//    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss");
//
//    @Override
//    public String getPluginName() {
//        return ProcessPluginEnum.DAN_MU_HL_VOD_CUT.getType();
//    }
//
//    @Override
//    public boolean process(String recordPath) {
//        File highlightFile = new File(recordPath, RecordConstant.HL_VIDEO);
//        if (highlightFile.exists()) {
//            log.info("highlight file already existed, will skip, path: {}", recordPath);
//            return true;
//        }
//
//        // 1. 读取弹幕文件
//        List<DanmakuItem> danmakuItems = readDanmus(new File(recordPath, "danmu.csv").getAbsolutePath());
//
//        // 2. 读取所有ts文件列表
//        List<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false)
//                .stream()
//                .sorted(Comparator.comparingInt(v -> VideoFileUtil.genIndex(v.getName())))
//                .collect(Collectors.toList());
//        if (CollectionUtils.isEmpty(videos)) {
//            log.info("empty ts video file, will skip, path: {}", recordPath);
//            return true;
//        }
//
//        // 3. 找出弹幕密度最高的10个
//        List<TsDanmuInfo> tsDamuInfos = getTsDamuInfos(videos, danmakuItems);
//        List<DanmuSlideWindow> windows = calculateSlidingWindows(tsDamuInfos);
//        List<DanmuSlideWindow> topWindows = windows.stream()
//                .sorted((w1, w2) -> Double.compare(w2.getAvgDensity(), w1.getAvgDensity()))
//                .limit(TOP_N)
//                .collect(Collectors.toList());
//        if (topWindows.isEmpty()) {
//            log.error("no high light windows, will skip");
//            return true;
//        }
//        for (DanmuSlideWindow topWindow : topWindows) {
//            log.info("window: [{}, {}], avgDensity: {}", topWindow.getStartIndex(), topWindow.getEndIndex(), topWindow.getAvgDensity());
//        }
//
//
//        // 4. 进行合并视频
//        String timeStr = highlightFile.getParentFile().getName();
//        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2) + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
//        boolean success = videoMergeService.mergeMultiWithFadeV2(buildMergeFileNames(topWindows), highlightFile, title);
//
//        // 5. 发消息
//        String msgPrefix = success ? "合并视频完成！路径为：" : "合并视频失败！路径为：";
//        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());
//
//
//        return false;
//    }
//
//
//    @Data
//    static class TsDanmuInfo {
//        String fileName;
//        long startTime;
//        long endTime;
//        int danmakuCount;
//
//        public TsDanmuInfo(File tsFile) {
//            this.fileName = tsFile.getName();
//            this.startTime = VideoFileUtil.getCreateTime(tsFile);
//            this.endTime = VideoFileUtil.getLastModifiedTime(tsFile);
//        }
//    }
//
//    @Data
//    static class DanmuSlideWindow {
//        private int startIndex; // 窗口起始TS索引
//        private int endIndex;   // 窗口结束TS索引
//        private double avgDensity; // 平均密度
//        private List<TsDanmuInfo> tsDanmuInfos; // 窗口包含的TS文件
//
//        public DanmuSlideWindow(int startIndex, int endIndex, double avgDensity, List<TsDanmuInfo> tsDanmuInfos) {
//            this.startIndex = startIndex;
//            this.endIndex = endIndex;
//            this.avgDensity = avgDensity;
//            this.tsDanmuInfos = tsDanmuInfos;
//        }
//    }
//
//    public static void main(String[] args) {
//        // 配置文件路径(根据实际情况修改)
//        String tsDirectory = "path/to/ts/files";
//        String danmuFile = "path/to/danmu.csv";
//
//        try {
//            // 1. 读取并解析所有TS文件，获取时间戳信息
//            List<TsDanmuInfo> tsDanmuInfos = readAndParseTSFiles(tsDirectory);
//            if (tsDanmuInfos.isEmpty()) {
//                System.out.println("未找到TS文件");
//                return;
//            }
//            System.out.println("已解析 " + tsDanmuInfos.size() + " 个TS文件");
//
//            // 2. 读取并解析弹幕数据，获取时间戳列表
//            List<Long> danmuTimestamps = readDanmus(danmuFile);
//            if (danmuTimestamps.isEmpty()) {
//                System.out.println("未找到有效弹幕数据");
//                return;
//            }
//            System.out.println("已解析 " + danmuTimestamps.size() + " 条弹幕数据");
//
//            // 3. 计算每个TS文件的弹幕数量和密度
//            getTsDamuInfos(tsDanmuInfos, danmuTimestamps);
//
//            // 4. 使用滑动窗口计算平均密度
//            List<DanmuSlideWindow> windows = calculateSlidingWindows(tsDanmuInfos);
//            if (windows.isEmpty()) {
//                System.out.println("无法形成有效窗口");
//                return;
//            }
//
//            // 5. 按平均密度排序，选出前10个窗口
//            List<DanmuSlideWindow> topWindows = windows.stream()
//                    .sorted((w1, w2) -> Double.compare(w2.avgDensity, w1.avgDensity))
//                    .limit(TOP_N)
//                    .collect(Collectors.toList());
//
//            // 6. 输出结果
//            printTopWindows(topWindows);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//
//    /**
//     * 读取弹幕CSV文件，提取时间戳
//     */
//    private static List<DanmakuItem> readDanmus(String filePath) {
//        List<DanmakuItem> items = new ArrayList<>();
//        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
//            String line;
//            // 跳过表头
//            br.readLine();
//
//            while ((line = br.readLine()) != null) {
//                String[] parts = line.split(",", 2); // 只分割第一个逗号
//                if (parts.length > 0) {
//                    items.add(new DanmakuItem(Long.valueOf(parts[0]), parts[1]));
//                }
//            }
//        } catch (Exception e) {
//            log.error("read danmu csv error, file: {}", filePath, e);
//        }
//
//        // 排序时间戳，便于后续二分查找
//        Collections.sort(items);
//        return items;
//    }
//
//    /**
//     * 计算每个TS文件的弹幕数量和密度
//     */
//    private static List<TsDanmuInfo> getTsDamuInfos(List<File> tsFiles, List<DanmakuItem> danmakuItems) {
//        List<TsDanmuInfo> tsDanmuInfos = new ArrayList<>();
//        for (File file : tsFiles) {
//            TsDanmuInfo tsDanmuInfo = new TsDanmuInfo(file);
//            int startIdx = findFirstTimestampIndex(danmakuItems, tsDanmuInfo.getStartTime());
//            int endIdx = findFirstTimestampIndex(danmakuItems, tsDanmuInfo.getEndTime());
//            tsDanmuInfo.setDanmakuCount(endIdx - startIdx);
//            tsDanmuInfos.add(tsDanmuInfo);
//        }
//        return tsDanmuInfos;
//    }
//
//    /**
//     * 二分查找第一个大于等于目标时间戳的索引
//     */
//    private static int findFirstTimestampIndex(List<DanmakuItem> items, long target) {
//        int low = 0;
//        int high = items.size();
//
//        while (low < high) {
//            int mid = (low + high) / 2;
//            if (items.get(mid).getTs() < target) {
//                low = mid + 1;
//            } else {
//                high = mid;
//            }
//        }
//        return low;
//    }
//
//    /**
//     * 计算滑动窗口的平均密度
//     */
//    private static List<DanmuSlideWindow> calculateSlidingWindows(List<TsDanmuInfo> tsDanmuInfos) {
//        List<DanmuSlideWindow> windows = new ArrayList<>();
//
//        // 窗口数量 = TS文件总数 - 窗口大小 + 1
//        int windowCount = tsDanmuInfos.size() - WINDOW_SIZE + 1;
//        if (windowCount <= 0) {
//            return windows;
//        }
//
//        // 计算第一个窗口的密度和
//        double currentSum = 0;
//        for (int i = 0; i < WINDOW_SIZE; i++) {
//            currentSum += tsDanmuInfos.get(i).getDanmakuCount();
//        }
//
//        // 添加第一个窗口
//        windows.add(new DanmuSlideWindow(0, WINDOW_SIZE - 1,
//                currentSum / WINDOW_SIZE,
//                tsDanmuInfos.subList(0, WINDOW_SIZE)));
//
//        // 滑动窗口计算(复用前一个窗口的和，只需加减首尾元素)
//        for (int i = 1; i < windowCount; i++) {
//            // 减去移除窗口的第一个元素，加上新加入窗口的元素
//            currentSum = currentSum - tsDanmuInfos.get(i - 1).getDanmakuCount() + tsDanmuInfos.get(i + WINDOW_SIZE - 1).getDanmakuCount();
//            double avgDensity = currentSum / WINDOW_SIZE;
//
//            windows.add(new DanmuSlideWindow(i, i + WINDOW_SIZE - 1, avgDensity, tsDanmuInfos.subList(i, i + WINDOW_SIZE)));
//        }
//
//        return windows;
//    }
//
//    /**
//     * 筛选Top N窗口，处理重叠问题
//     */
//    private static List<DanmuSlideWindow> selectTopWindows(List<DanmuSlideWindow> windows) {
//        // 先按密度降序排序
//        List<DanmuSlideWindow> sortedWindows = new ArrayList<>(windows);
//        sortedWindows.sort((w1, w2) -> Double.compare(w2.getAvgDensity(), w1.getAvgDensity()));
//
//        List<DanmuSlideWindow> selected = new ArrayList<>();
//        Set<Integer> usedIndices = new HashSet<>();
//
//        for (DanmuSlideWindow window : sortedWindows) {
//            if (selected.size() >= TOP_N) {
//                break;
//            }
//
//            // 检查当前窗口是否与已选窗口重叠
//            boolean isOverlapping = false;
//            for (int i = window.getStartIndex(); i <= window.getEndIndex(); i++) {
//                if (usedIndices.contains(i)) {
//                    isOverlapping = true;
//                    break;
//                }
//            }
//
//            // 不重叠则选中
//            if (!isOverlapping) {
//                selected.add(window);
//                // 记录当前窗口使用的索引
//                usedIndices.addAll(Arrays.stream())
//                for (int i = window.startIndex; i <= window.endIndex; i++) {
//                    usedIndices.add(i);
//                }
//            }
//        }
//
//        private List<List<String>> buildMergeFileNames (List < DanmuSlideWindow > intervals, Collection < File > files){
//            Map<Integer, String> name2PathMap = files.stream().collect(
//                    Collectors.toMap(file -> VideoFileUtil.genIndex(file.getName()), File::getAbsolutePath, (a, b) -> b)
//            );
//            List<List<String>> res = Lists.newArrayList();
//            for (Pair<Integer, Integer> interval : intervals) {
//                List<String> tmp = Lists.newArrayList();
//                for (int i = interval.getLeft(); i < interval.getRight() + 1; i++) {
//                    if (name2PathMap.containsKey(i)) {
//                        tmp.add(name2PathMap.get(i));
//                    }
//                }
//                res.add(tmp);
//            }
//            return res;
//        }
//    }
