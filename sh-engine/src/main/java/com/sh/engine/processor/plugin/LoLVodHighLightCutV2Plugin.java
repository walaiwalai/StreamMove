package com.sh.engine.processor.plugin;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.DateUtil;
import com.sh.config.utils.FileStoreUtil;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.ScreenshotCmd;
import com.sh.engine.model.ffmpeg.VideoDurationDetectCmd;
import com.sh.engine.model.highlight.HlScoredInterval;
import com.sh.engine.model.lol.LoLPicData;
import com.sh.engine.model.lol.LolSequenceStatistic;
import com.sh.engine.model.video.VideoInterval;
import com.sh.engine.model.video.VideoSnapPoint;
import com.sh.engine.service.process.VideoMergeService;
import com.sh.engine.util.RegexUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author caiwen
 * @Date 2025 08 09 09 41
 **/
@Component
@Slf4j
public class LoLVodHighLightCutV2Plugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private MsgSendService msgSendService;
    @Value("${ocr.server.host}")
    private String ocrHost;
    @Value("${ocr.server.port}")
    private String ocrPort;

    /**
     * 4秒一张接入
     */
    private static final int SNAP_INTERVAL_SECOND = 4;
    /**
     * 精彩片段的视频片段总数
     */
    private static final int MAX_HIGH_LIGHT_SEG_COUNT = 10;

    private static final int OCR_INTERVAL_NUM = 5;
    private static final Map<String, Integer> LAST_OCR_K_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_D_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_A_MAP = Maps.newConcurrentMap();
    public static final List<Integer> BLANK_KADS = Lists.newArrayList(-1, -1, -1);

    /**
     * 测试的kad大概位置
     */
    private static final String KAD_TEST_CORP_EXP = "crop=in_w/2:100:in_w/2:0";

    /**
     * kda + 击杀细节 截图位置参数
     */
    private static final String KAD_CORP_EXP = "crop=80:30:in_w*867/1000:0";
    private static final String KILL_DETAIL_CORP_EXP = "crop=270:290:in_w*86/100:in_h*3/16";

    private static final String KAD_SNAPSHOT_DIR_NAME = "kda-snapshot";
    private static final String KAD_TEST_SNAPSHOT_DIR_NAME = "kda-test-snapshot";
    private static final String DETAIL_SNAPSHOT_DIR_NAME = "detail-snapshot";


    @Override
    public String getPluginName() {
        return ProcessPluginEnum.LOL_HL_VOD_CUT_V2.getType();
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, RecordConstant.HL_VIDEO);
        if (highlightFile.exists()) {
            log.info("highlight file already existed, will skip, path: {}", recordPath);
            return true;
        }

        List<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"mp4"}, false)
                .stream()
                .sorted(Comparator.comparingInt(VideoFileUtil::getVideoIndex))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videos)) {
            log.info("empty ts video file, will skip, path: {}", recordPath);
            return true;
        }

        // 2 精确的kda的截图
        List<File> snapshotFiles = kdaSnapShot(recordPath, videos);
        if (CollectionUtils.isEmpty(snapshotFiles)) {
            return true;
        }

        // 3.解析截图文件
        List<VideoSnapPoint> videoSnapPoints = parseVideoPointSequence(snapshotFiles, videos);

        // 4. ocr + 位置识别
        List<LoLPicData> datas = parseOCRResult(videoSnapPoints, recordPath);

        // 5. 找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_SEG_COUNT);
        List<HlScoredInterval> topNIntervals = statistic.getTopNIntervals();
        if (CollectionUtils.isEmpty(topNIntervals)) {
            log.info("no highlight video, will skip, path: {}", recordPath);
            return true;
        }
        log.info("find topNIntervals: {}", JSON.toJSONString(topNIntervals));

        // 6. 找出对应的视频开始和结束点
        List<VideoInterval> videoIntervals = Lists.newArrayList();
        for (HlScoredInterval interval : topNIntervals) {
            int left = interval.getLeftIndex();
            int right = interval.getRightIndex();
            File startVideo = videoSnapPoints.get(left).getFromVideo();
            File endVideo = videoSnapPoints.get(right).getFromVideo();
            double videoStartSecond = videoSnapPoints.get(left).getSecondFromVideoStart();
            double videoEndSecond = videoSnapPoints.get(right).getSecondFromVideoStart() + SNAP_INTERVAL_SECOND;
            if (!StringUtils.equals(startVideo.getAbsolutePath(), endVideo.getAbsolutePath())) {
                // 不同的视频片段不需要合并
                continue;
            }

            videoIntervals.add(new VideoInterval(startVideo, videoStartSecond, videoEndSecond));
        }
        log.info("find videoIntervals: {}", JSON.toJSONString(videoIntervals));

        // 7. 进行合并视频
        String timeStr = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2) + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        boolean success = videoMergeService.mergeWithCover(videoIntervals, highlightFile, title);

        // 8. 发消息
        String msgPrefix = success ? "合并视频完成！路径为：" : "合并视频失败！路径为：";
        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());

        return success;
    }

    /**
     * 获取视频点（有序的）
     *
     * @param shotPics 截图文件
     * @param videos   视频文件
     * @return 有序的视频点
     */
    private List<VideoSnapPoint> parseVideoPointSequence(List<File> shotPics, List<File> videos) {
        // 1. 所有视频的长度
        Map<String, File> videoFileMap = videos.stream().collect(Collectors.toMap(FileUtil::getPrefix, v -> v));
        Map<String, Double> videoDurationMap = videos.stream()
                .collect(Collectors.toMap(FileUtil::getPrefix, v -> {
                    VideoDurationDetectCmd cmd = new VideoDurationDetectCmd(v.getAbsolutePath());
                    cmd.execute(100);
                    return cmd.getDurationSeconds();
                }));

        // 2. 构建视频开始时间映射（相对于整个录制序列的起始时间）
        Map<String, Double> videoStartMap = new LinkedHashMap<>();
        double currentStart = 0.0;
        for (File video : videos) {
            String prefix = FileUtil.getPrefix(video.getName());
            videoStartMap.put(prefix, currentStart);
            currentStart += videoDurationMap.getOrDefault(prefix, 0.0);
        }

        // 4. 解析截图文件，构建VideoScreenshot对象
        List<VideoSnapPoint> result = new ArrayList<>();
        for (File shotPic : shotPics) {
            Integer snapshotIndex = VideoFileUtil.getSnapshotIndex(shotPic);
            String sourceFileName = VideoFileUtil.getSnapshotSourceFileName(shotPic);

            File fromVideo = videoFileMap.get(sourceFileName);
            double secondFromVideoStart = (snapshotIndex - 1) * SNAP_INTERVAL_SECOND;

            // 计算从整个录制开始到截图的时间（全局时间）
            double videoStartTime = videoStartMap.getOrDefault(sourceFileName, 0.0);
            double secondFromRecordingStart = videoStartTime + secondFromVideoStart;

            // 构建并添加VideoScreenshot对象
            VideoSnapPoint screenshot = new VideoSnapPoint();
            screenshot.setSnapshotPic(shotPic);
            screenshot.setFromVideo(fromVideo);
            screenshot.setSecondFromVideoStart(secondFromVideoStart);
            screenshot.setSecondFromRecordingStart(secondFromRecordingStart);
            result.add(screenshot);
        }

        // 5. 按全局录制时间排序
        Collections.sort(result);
        return result;
    }

    private List<File> kdaSnapShot(String recordPath, List<File> videos) {
        // 找到kda确切位置
        String kadCorpExp = findAccurateKdaCorpExp(recordPath, videos.get(0));

        // 截图保存路径
        File snapShotDir = new File(recordPath, KAD_SNAPSHOT_DIR_NAME);
        snapShotDir.mkdirs();

        // 多线程截图
        List<File> allSnapshotFiles = new ArrayList<>();
        for (File video : videos) {
            allSnapshotFiles.addAll(shotSingleVideo(video, snapShotDir, kadCorpExp));
        }
        return allSnapshotFiles;
    }


    private List<File> shotSingleVideo(File video, File snapShotDir, String kadCorpExp) {
        Map<String, List<String>> video2SnapShotMap = new HashMap<>();
        // 可能之前已经截图过了
        File recordFile = new File(snapShotDir, "snap-record.json");
        if (recordFile.exists()) {
            video2SnapShotMap = FileStoreUtil.loadFromFile(recordFile, new TypeReference<Map<String, List<String>>>() {
            });
        }

        // 执行截图
        List<File> snapshotFiles = Lists.newArrayList();
        if (video2SnapShotMap.containsKey(video.getName())) {
            List<File> existedSnapFiles = video2SnapShotMap.get(video.getName()).stream()
                    .map(name -> new File(snapShotDir, name))
                    .collect(Collectors.toList());
            snapshotFiles.addAll(existedSnapFiles);
        } else {
            ScreenshotCmd snapshotCmd = new ScreenshotCmd(video, snapShotDir, 0, 99999, kadCorpExp, SNAP_INTERVAL_SECOND, 1, false);
            snapshotCmd.execute(3600);
            snapshotFiles.addAll(snapshotCmd.getSnapshotFiles());

            // 记录一下
            video2SnapShotMap.put(video.getName(), snapshotFiles.stream().map(File::getName).collect(Collectors.toList()));
            FileStoreUtil.saveToFile(recordFile, video2SnapShotMap);
        }

        return snapshotFiles;
    }


    /**
     * 找出精确的kda截图位置
     *
     * @param recordPath  录制地址
     * @param sampleVideo 样本视频
     * @return
     */
    private String findAccurateKdaCorpExp(String recordPath, File sampleVideo) {
        File testSnapShotDir = new File(recordPath, KAD_TEST_SNAPSHOT_DIR_NAME);
        testSnapShotDir.mkdirs();

        // 从文件中找
        File accurateCorpFile = new File(testSnapShotDir, "accurate-corp.json");
        if (accurateCorpFile.exists()) {
            Map<String, String> corpMap = FileStoreUtil.loadFromFile(accurateCorpFile, new TypeReference<Map<String, String>>() {
            });
            if (corpMap.containsKey(sampleVideo.getName())) {
                log.info("find kda corp exp success, video: {}, corpExp: {}", sampleVideo.getName(), corpMap.get(sampleVideo.getName()));
                return corpMap.get(sampleVideo.getName());
            }
        }

        // 视频时长获取
        VideoDurationDetectCmd cmd = new VideoDurationDetectCmd(sampleVideo.getAbsolutePath());
        cmd.execute(60);
        double endSecond = cmd.getDurationSeconds();


        int batchCnt = 20;
        int startSecond = 0;
        String accurateCorpExp = null;
        while (startSecond < endSecond) {
            if (StringUtils.isNotBlank(accurateCorpExp)) {
                break;
            }
            ScreenshotCmd screenshotCmd = new ScreenshotCmd(sampleVideo, testSnapShotDir, startSecond, batchCnt, KAD_TEST_CORP_EXP, SNAP_INTERVAL_SECOND, 1, false);
            screenshotCmd.execute(1800);
            List<File> snapshotFiles = screenshotCmd.getSnapshotFiles();
            for (File snapshotFile : snapshotFiles) {
                List<List<Integer>> kdaBoxes;
                try {
                    kdaBoxes = detectKDABox(snapshotFile.getAbsolutePath());
                } catch (Exception e) {
                    log.error("error to detect kda box, path: {}", snapshotFile.getAbsolutePath(), e);
                    continue;
                }
                if (CollectionUtils.isEmpty(kdaBoxes)) {
                    continue;
                }
                // 精确的裁剪参数
                String corpExp = genAccurateCorpExpByBox(kdaBoxes);
                if (StringUtils.isNotBlank(corpExp)) {
                    accurateCorpExp = corpExp;
                    break;
                }
            }

            startSecond += batchCnt * SNAP_INTERVAL_SECOND;
        }

        // 存一下
        if (StringUtils.isBlank(accurateCorpExp)) {
            accurateCorpExp = KAD_CORP_EXP;
        }
        log.info("find accurate kda corp exp: {}", accurateCorpExp);
        FileStoreUtil.saveToFile(accurateCorpFile, Maps.newHashMap(ImmutableMap.of(sampleVideo.getName(), accurateCorpExp)));

        return accurateCorpExp;
    }

    private static String genAccurateCorpExpByBox(List<List<Integer>> boxes) {
        if (boxes == null || boxes.size() != 4) {
            log.error("The boxes list must contain exactly 4 points, boxes: {}", boxes);
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (List<Integer> point : boxes) {
            int x = point.get(0);
            int y = point.get(1);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        int width = maxX - minX;
        int height = maxY - minY;
        return String.format("crop=%d:%d:in_w/2+%d:%d", width + 20, height + 10, minX, minY - 5);
    }


    private List<LoLPicData> parseOCRResult(List<VideoSnapPoint> points, String recordPath) {
        File detailDir = new File(recordPath, DETAIL_SNAPSHOT_DIR_NAME);
        detailDir.mkdirs();

        LoLPicData lastPic = LoLPicData.genBlank();
        List<LoLPicData> res = Lists.newArrayList();
        List<List<VideoSnapPoint>> batchScreenshot = Lists.partition(points, OCR_INTERVAL_NUM);
        for (List<VideoSnapPoint> shots : batchScreenshot) {
            VideoSnapPoint testShotPic = shots.get(shots.size() - 1);

            // 当前批的最后一个进行测试ocr
            LoLPicData gLastPic = testParse(testShotPic.getSnapshotPic().getAbsolutePath());

            // 前后kda是否一样，一样就可以跳过了
            boolean isSkip = skipOcr(lastPic, gLastPic);

            if (isSkip) {
                res.addAll(buildSameKdaData(lastPic, shots.size()));
            } else {
                res.addAll(buildNotSameKdaData(recordPath, lastPic, shots));
            }
            // 更新最后一个
            lastPic = gLastPic;
        }
        return res;
    }


    private boolean skipOcr(LoLPicData lastPic, LoLPicData cur) {
        boolean sameKda = lastPic.compareKda(cur);
        boolean invalid = cur.beInvalid();
        return sameKda && !invalid;
    }

    private boolean highlightOccur(LoLPicData lastPic, LoLPicData cur) {
        if (!lastPic.beValid()) {
            return false;
        }
        if (!cur.beValid()) {
            return false;
        }
        return cur.getK() > lastPic.getK() || cur.getA() > lastPic.getA();
    }

    private List<LoLPicData> buildSameKdaData(LoLPicData lastPic, int size) {
        // 最后一个跟上一次结果一样，不需要进行额外的ocr，直接跟上次一样
        List<LoLPicData> datas = Lists.newArrayList();
        for (int i = 0; i < size; i++) {
            LoLPicData cur = new LoLPicData(lastPic.getK(), lastPic.getD(), lastPic.getA());
            datas.add(cur);
        }
        return datas;
    }

    private List<LoLPicData> buildNotSameKdaData(String recordPath, LoLPicData lastPic, List<VideoSnapPoint> idxes) {
        File detailDir = new File(recordPath, DETAIL_SNAPSHOT_DIR_NAME);
        List<LoLPicData> datas = Lists.newArrayList();
        LoLPicData tmp = lastPic.copy();
        for (VideoSnapPoint idx : idxes) {
            // 不一样，这个区间内的每个都进行ocr
            LoLPicData cur = parseKda(idx.getSnapshotPic().getAbsolutePath());

            // 精彩时刻进行击杀细节
            if (highlightOccur(tmp, cur)) {
                File fromVideo = idx.getFromVideo();
                ScreenshotCmd snapshotCmd = new ScreenshotCmd(fromVideo, detailDir, (int) Math.round(idx.getSecondFromVideoStart()), 1, KILL_DETAIL_CORP_EXP, SNAP_INTERVAL_SECOND, 1, false);
                snapshotCmd.execute(600);
                if (CollectionUtils.isEmpty(snapshotCmd.getSnapshotFiles())) {
                    File detailPath = snapshotCmd.getSnapshotFiles().get(0);
                    cur.setHeroKADetail(parseDetailByVisDet(detailPath));
                }
            }

            tmp = cur;
            datas.add(cur);
        }
        return datas;
    }

    private LoLPicData testParse(String path) {
        List<Integer> kad = parseKDAByOCR(path);
        if (kad.size() < 3) {
            return LoLPicData.genInvalid();
        } else {
            return new LoLPicData(kad.get(0), kad.get(1), kad.get(2));
        }
    }

    private LoLPicData parseKda(String path) {
        LoLPicData data;
        List<Integer> kad = parseKDAByOCR(path);
        if (kad.size() < 3) {
            data = parseFromCache();
        } else {
            data = parseFromKad(kad);
        }
        return data;
    }

    private LoLPicData parseFromCache() {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        Integer lastK = LAST_OCR_K_MAP.getOrDefault(streamerName, -1);
        Integer lastD = LAST_OCR_D_MAP.getOrDefault(streamerName, -1);
        Integer lastA = LAST_OCR_A_MAP.getOrDefault(streamerName, -1);
        log.info("ocr error, will use last cache., last kda: {}/{}/{}.", lastK, lastD, lastA);
        return new LoLPicData(lastK, lastD, lastA);
    }

    private LoLPicData parseFromKad(List<Integer> nums) {
        String streamerName = StreamerInfoHolder.getCurStreamerName();
        LAST_OCR_K_MAP.put(streamerName, nums.get(0));
        LAST_OCR_D_MAP.put(streamerName, nums.get(1));
        LAST_OCR_A_MAP.put(streamerName, nums.get(2));
        return new LoLPicData(nums.get(0), nums.get(1), nums.get(2));
    }

    private List<Integer> parseKDAByOCR(String filePath) {
        File snapShotFile = new File(filePath);
        if (!snapShotFile.exists()) {
            return BLANK_KADS;
        }

        MediaType mediaType = MediaType.parse("application/json");
        Map<String, String> params = Maps.newHashMap();
        params.put("path", filePath);
        RequestBody body = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url("http://" + ocrHost + ":" + ocrPort + "/ocr")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        String ocrStr = JSON.parseObject(resp).getString("text");
        String score = JSON.parseObject(resp).getString("score");
        if (StringUtils.isBlank(ocrStr)) {
            log.info("parse no kad, file: {}.", snapShotFile.getAbsolutePath());
            return BLANK_KADS;
        }

        log.info("parse image success, file: {}, res: {}, confidence: {}.", filePath, ocrStr, score);
        return RegexUtil.getMatchList(ocrStr, "\\d+", false).stream()
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    private LoLPicData.HeroKillOrAssistDetail parseDetailByVisDet(File snapShotFile) {
        if (!snapShotFile.exists()) {
            return null;
        }

        MediaType mediaType = MediaType.parse("application/json");
        Map<String, String> params = Maps.newHashMap();
        params.put("path", snapShotFile.getAbsolutePath());
        RequestBody body = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url("http://" + ocrHost + ":" + ocrPort + "/lolKillVisDet")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONObject respObj = JSON.parseObject(resp);
        List<List<Float>> boxes = JSON.parseObject(respObj.getString("boxes"), new TypeReference<List<List<Float>>>() {
        });
        List<Integer> labelIds = JSON.parseObject(respObj.getString("labelIds"), new TypeReference<List<Integer>>() {
        });
        if (CollectionUtils.isEmpty(boxes)) {
            return null;
        }

        log.info("parse detail image success, file: {}, labelIds: {}.", snapShotFile.getAbsolutePath(), JSON.toJSONString(labelIds));
        return new LoLPicData.HeroKillOrAssistDetail(boxes, labelIds);
    }


    private List<List<Integer>> detectKDABox(String filePath) {
        File snapShotFile = new File(filePath);
        if (!snapShotFile.exists()) {
            return Lists.newArrayList();
        }

        MediaType mediaType = MediaType.parse("application/json");
        Map<String, String> params = Maps.newHashMap();
        params.put("path", filePath);
        RequestBody body = RequestBody.create(mediaType, JSON.toJSONString(params));
        Request request = new Request.Builder()
                .url("http://" + ocrHost + ":" + ocrPort + "/ocrDet")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        String resp = OkHttpClientUtil.execute(request);
        log.info("detect kda boxes resp, file: {}, res: {}.", snapShotFile.getAbsolutePath(), resp);
        JSONArray detectArrays = JSON.parseArray(resp);
        for (Object detectObj : detectArrays) {
            JSONObject detObj = (JSONObject) detectObj;
            String ocrText = detObj.getString("text");
            if (!isValidKadStr(ocrText)) {
                continue;
            }

            List<Integer> boxes = detObj.getJSONArray("boxes").toJavaList(Integer.class);
            float score = detObj.getFloat("score");
            List<List<Integer>> fourPoints = Lists.partition(boxes, 2);
            log.info("find kda boxed success, boxes: {}, text: {}, confidence: {}.", JSON.toJSONString(fourPoints), ocrText, score);
            return fourPoints;
        }
        return Lists.newArrayList();
    }


    private boolean isValidKadStr(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return StringUtils.split(text, "/").length == 3;
    }

    public static void main(String[] args) {
        File sourceFile = new File("G:\\stream_record\\download\\mytest-mac\\2025-06-16-16-16-16\\highlight.mp4");
        System.out.println(sourceFile.getName());
    }
}
