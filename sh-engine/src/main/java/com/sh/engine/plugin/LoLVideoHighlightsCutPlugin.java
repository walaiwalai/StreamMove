//package com.sh.engine.plugin;
//
//import com.google.common.collect.Lists;
//import com.sh.engine.model.ffmpeg.FfmpegCmd;
//import com.sh.engine.plugin.base.ScreenshotPic;
//import com.sh.engine.plugin.lol.LoLPicData;
//import com.sh.engine.plugin.lol.LolSequenceStatistic;
//import com.sh.engine.util.CommandUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.collections4.CollectionUtils;
//import org.apache.commons.lang3.StringUtils;
//import org.apache.commons.lang3.tuple.Pair;
//import org.springframework.stereotype.Component;
//
//import java.io.File;
//import java.util.List;
//
///**
// * 英雄联盟直播剪辑
// *
// * @Author caiwen
// * @Date 2024 01 13 22 43
// **/
//@Component
//@Slf4j
//public class LoLVideoHighlightsCutPlugin implements VideoHighlightsCutPlugin {
////    private static Map<String, LoLPicData> testMap = Maps.newHashMap();
//
////    static {
////        String excelFilePath = "E:\\project\\video-segs\\tag.xlsx";
////
////        try (FileInputStream fis = new FileInputStream(excelFilePath);
////             XSSFWorkbook workbook = new XSSFWorkbook(fis)) {
////
////            XSSFSheet sheet = workbook.getSheetAt(0);
////            Iterator<Row> rowIterator = sheet.iterator();
////
////            rowIterator.next();
////
////            // Iterate through the remaining rows
////            while (rowIterator.hasNext()) {
////                Row row = rowIterator.next();
////
////                testMap.put(
////                        row.getCell(0).getStringCellValue(),
////                        new LoLPicData(
////                                Double.valueOf(row.getCell(1).toString()).intValue(),
////                                Double.valueOf(row.getCell(2).toString()).intValue(),
////                                Double.valueOf(row.getCell(3).toString()).intValue()
////                        )
////                );
////            }
////        } catch (Exception e) {
////            e.printStackTrace();
////        }
////    }
//
//
//    @Override
//    public void doScreenshot(List<File> candidateVideos) {
//        for (File video : candidateVideos) {
//            snapShot(video);
//        }
//    }
//
//    @Override
//    public List<Pair<Integer, Integer>> filterTs(List<ScreenshotPic> orderedPics, Integer maxInterval) {
//        if (CollectionUtils.isEmpty(orderedPics)) {
//            return Lists.newArrayList();
//        }
//
//        List<LoLPicData> datas = Lists.newArrayList();
//        for (ScreenshotPic pic : orderedPics) {
//            // 2. 读取视频截图中的KDA
//            datas.add(parse(pic));
//        }
//
//        // 3. 分析KDA找出精彩片段
//        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, maxInterval);
//        return statistic.getPotentialIntervals();
//    }
//
//
//    private File snapShot(File segFile) {
//        String segFileName = segFile.getName();
//        String filePrefix = segFileName.substring(0, segFileName.lastIndexOf("."));
//
//        File snapShotFile = new File(segFile.getParent(), "snapshot");
//        File picFile = new File(snapShotFile, filePrefix + ".jpg");
//        if (picFile.exists()) {
//            // 已经存在不在重复裁剪
//            return picFile;
//        }
//
//        List<String> params = Lists.newArrayList(
//                "-i", segFile.getAbsolutePath(),
//                "-vf", "crop=100:50:in_w*17/20:0",
//                "-ss", "00:00:02",
//                "-frames:v", "1",
//                picFile.getAbsolutePath()
//        );
//        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
//        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
//        if (resCode == 0) {
//            log.info("get pic success, path: {}", segFile.getAbsolutePath());
//            return picFile;
//        } else {
//            log.info("get pic fail, path: {}", segFile.getAbsolutePath());
//            return null;
//        }
//    }
//
//    private LoLPicData parse(ScreenshotPic picFile) {
////        LoLPicData loLPicData = testMap.get(picFile.getPicFile().getName());
////        if (loLPicData == null || loLPicData.getK() == -1) {
////            return null;
////        }
////        loLPicData.setTargetIndex(picFile.getIndex());
////        return loLPicData;
//        return null;
//
//    }
//
////    public static void main(String[] args) {
////        LoLVideoHighlightsCutPlugin plugin = new LoLVideoHighlightsCutPlugin();
////        List<ScreenshotPic> picFiles = Lists.newArrayList();
////        for (int i = 1; i < 4471; i++) {
////            picFiles.add(
////                    new ScreenshotPic(new File("E:\\project\\video-segs\\pics", "Guma_seg-" + i + ".jpg"), i)
////            );
////        }
////        List<Pair<Integer, Integer>> pairs = plugin.filterTs(picFiles, 40);
////
////
////        // 3. 根据区间重新下载视频
////        List<Integer> batchIndexes = Lists.newArrayList();
////        for (Pair<Integer, Integer> pair : pairs) {
////            Integer start = pair.getLeft();
////            Integer end = pair.getRight();
////            for (int i = start; i < end + 1; i++) {
////                batchIndexes.add(i);
////            }
////        }
////
////        // 4.合并视频
////        mergeVideos(batchIndexes, new File("E:\\project\\video-segs", "highlight.mp4"));
////
////    }
//
////    private static boolean mergeVideos(List<Integer> batchIndexes, File targetVideo) {
////        File mergeListFile = new File(targetVideo.getParent(), "merge.txt");
////        List<String> lines = batchIndexes.stream()
////                .map(index -> {
////                    File segFile = new File(targetVideo.getParent(), "seg-" + index + ".ts");
////                    if (!segFile.exists()) {
////                        return null;
////                    }
////                    return "file " + segFile.getAbsolutePath().replace("\\", "\\\\");
////                })
////                .filter(Objects::nonNull)
////                .collect(Collectors.toList());
////
////        try {
////            IOUtils.write(StringUtils.join(lines, "\n"), new FileOutputStream(mergeListFile), "utf-8");
////        } catch (IOException e) {
////            log.error("write merge list file fail, savePath: {}", mergeListFile.getAbsolutePath(), e);
////        }
////
////        // 2. 使用FFmpeg合并视频
////        String targetPath = targetVideo.getAbsolutePath();
////        String command = "-f concat -safe 0 -i " + mergeListFile.getAbsolutePath() + " -c:v copy -c:a copy -r 30 " + targetPath;
////        FfmpegCmd ffmpegCmd = new FfmpegCmd(command);
////
////        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
////        if (resCode == 0) {
////            log.info("merge video success, path: {}", targetPath);
////            return true;
////        } else {
////            log.info("merge video fail, path: {}", targetPath);
////            return false;
////        }
////    }
//}
