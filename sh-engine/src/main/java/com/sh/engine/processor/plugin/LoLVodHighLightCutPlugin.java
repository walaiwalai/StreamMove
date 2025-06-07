package com.sh.engine.processor.plugin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.ExecutorPoolUtil;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.VideoFileUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.constant.ProcessPluginEnum;
import com.sh.engine.constant.RecordConstant;
import com.sh.engine.model.ffmpeg.FFmpegProcessCmd;
import com.sh.engine.model.lol.LoLPicData;
import com.sh.engine.model.lol.LolSequenceStatistic;
import com.sh.engine.service.process.VideoMergeService;
import com.sh.engine.util.DateUtil;
import com.sh.engine.util.RegexUtil;
import com.sh.message.service.MsgSendService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * todo
 * 1. 召唤师技能、血量的ocr
 *
 * @Author caiwen
 * @Date 2024 01 26 22 18
 **/
@Component
@Slf4j
public class LoLVodHighLightCutPlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;
    @Resource
    private MsgSendService msgSendService;
    @Value("${ocr.server.host}")
    private String ocrHost;
    @Value("${ocr.server.port}")
    private String ocrPort;

    /**
     * 精彩片段的视频片段总数
     */
    private static final int MAX_HIGH_LIGHT_SEG_COUNT = 100;

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
        return ProcessPluginEnum.LOL_HL_VOD_CUT.getType();
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, RecordConstant.LOL_HL_VIDEO);
        if (highlightFile.exists()) {
            log.info("highlight file already existed, will skip, path: {}", recordPath);
            return true;
        }

        List<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false)
                .stream()
                .sorted(Comparator.comparingInt(v -> VideoFileUtil.genIndex(v.getName())))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videos)) {
            log.info("empty ts video file, will skip, path: {}", recordPath);
            return true;
        }


        // 1. 找到kda确切位置
        String kadCorpExp = findAccurateKdaCorpExp(recordPath, videos);

        // 1.1 kda的截图(多线程)
        kdaSnapShot(recordPath, videos, kadCorpExp);

        // 1.2 ocr + 位置识别（串行）
        List<LoLPicData> datas = parseSeqPicsV2(videos, recordPath).stream()
                .filter(d -> d.getTargetIndex() != null)
                .collect(Collectors.toList());

        // 3. 找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_SEG_COUNT);
        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();
        if (CollectionUtils.isEmpty(potentialIntervals)) {
            log.info("no highlight video, will skip, path: {}", recordPath);
            return true;
        }

        // 4. 进行合并视频
        String timeStr = highlightFile.getParentFile().getName();
        String title = DateUtil.describeTime(timeStr, DateUtil.YYYY_MM_DD_HH_MM_SS_V2) + "\n" + StreamerInfoHolder.getCurStreamerName() + "直播精彩片段";
        boolean success = videoMergeService.mergeMultiWithFadeV2(buildMergeFileNames(potentialIntervals, videos), highlightFile, title);

        // 5. 发消息
        String msgPrefix = success ? "合并视频完成！路径为：" : "合并视频失败！路径为：";
        msgSendService.sendText(msgPrefix + highlightFile.getAbsolutePath());

        return success;
    }

    private String genKdaSnapshotPath(String recordPath, Integer index) {
        return new File(new File(recordPath, KAD_SNAPSHOT_DIR_NAME), VideoFileUtil.genSnapshotName(index)).getAbsolutePath();
    }

    private String genKdaTestSnapshotPath(String recordPath, Integer index) {
        return new File(new File(recordPath, KAD_TEST_SNAPSHOT_DIR_NAME), VideoFileUtil.genSnapshotName(index)).getAbsolutePath();
    }

    private String genKillDetailSnapshotPath(String recordPath, Integer index) {
        return new File(new File(recordPath, DETAIL_SNAPSHOT_DIR_NAME), VideoFileUtil.genSnapshotName(index)).getAbsolutePath();
    }

    private void kdaSnapShot(String recordPath, List<File> videos, String kadCorpExp) {
        File snapShotDir = new File(recordPath, KAD_SNAPSHOT_DIR_NAME);
        if (!snapShotDir.exists()) {
            snapShotDir.mkdir();
        }

        ExecutorService snapshotPool = ExecutorPoolUtil.getSnapshotPool();
        CountDownLatch latch = new CountDownLatch(videos.size());
        for (File video : videos) {
            CompletableFuture.runAsync(
                            () -> doSnapShot(recordPath, video.getName(), snapShotDir, kadCorpExp), snapshotPool)
                    .whenComplete((isSuccess, throwable) -> {
                        latch.countDown();
                    });
        }

        // 等待截图完成
        try {
            latch.await();
        } catch (InterruptedException e) {
        }
    }

    /**
     * 找出精确的kda截图位置
     *
     * @param recordPath
     * @param videos
     * @return
     */
    private String findAccurateKdaCorpExp(String recordPath, List<File> videos) {
        File testSnapShotDir = new File(recordPath, KAD_TEST_SNAPSHOT_DIR_NAME);
        if (!testSnapShotDir.exists()) {
            testSnapShotDir.mkdir();
        }
        for (int i = 0; i < videos.size(); i++) {
            File video = videos.get(i);
            if (i % 10 != 0) {
                continue;
            }
            // 截图
            doSnapShot(recordPath, video.getName(), testSnapShotDir, KAD_TEST_CORP_EXP);
            String testPath = genKdaTestSnapshotPath(recordPath, VideoFileUtil.genIndex(video.getName()));
            // 获取精确的kadbox
            List<List<Integer>> kdaBoxes = detectKDABox(testPath);
            if (CollectionUtils.isEmpty(kdaBoxes)) {
                continue;
            }
            // 精确的裁剪参数
            String corpExp = genAccurateCorpExpByBox(kdaBoxes);
            if (StringUtils.isNotBlank(corpExp)) {
                log.info("find accurate kda corp exp: {}", corpExp);
                return corpExp;
            }
        }

        return KAD_CORP_EXP;
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

    private void doSnapShot(String recordPath, String segFileName, File snapShotDir, String corpExp) {
        int videoIndex = VideoFileUtil.genIndex(segFileName);
        File sourceFile = new File(recordPath, segFileName);
        File targetFile = new File(snapShotDir, VideoFileUtil.genSnapshotName(videoIndex));
        if (targetFile.exists()) {
            log.info("target snap file existed, will skip, path: {}", targetFile.getAbsolutePath());
            return;
        }

        List<String> params = Lists.newArrayList(
                "ffmpeg", "-y",
                "-i", sourceFile.getAbsolutePath(),
                "-vf", corpExp,
                "-ss", "00:00:00",
                "-frames:v", "1",
                targetFile.getAbsolutePath()
        );
        FFmpegProcessCmd processCmd = new FFmpegProcessCmd(StringUtils.join(params, " "), false, false);
        processCmd.execute(10);
        if (processCmd.isEndNormal()) {
            if (videoIndex % 20 == 0) {
                log.info("get pic success, path: {}", sourceFile.getAbsolutePath());
            }
        } else {
            log.error("get pic fail, path: {}", sourceFile.getAbsolutePath());
        }
    }

    private List<LoLPicData> parseSeqPicsV2(Collection<File> videos, String recordPath) {
        // 1.构建index到截图的映射
        List<Integer> indexes = videos.stream()
                .map(video -> VideoFileUtil.genIndex(video.getName()))
                .collect(Collectors.toList());

        // 2. 遍历index列表，分析前后kad
        LoLPicData lastPic = LoLPicData.genBlank();
        List<LoLPicData> res = Lists.newArrayList();
        File detailDir = new File(recordPath, DETAIL_SNAPSHOT_DIR_NAME);
        if (!detailDir.exists()) {
            detailDir.mkdir();
        }

        List<List<Integer>> batchIndexes = Lists.partition(indexes, OCR_INTERVAL_NUM);
        for (List<Integer> idxes : batchIndexes) {
            Integer testIndex = idxes.get(idxes.size() - 1);

            // 当前批的最后一个进行测试ocr
            LoLPicData gLastPic = testParse(genKdaSnapshotPath(recordPath, testIndex));

            // 前后kda是否一样，一样就可以跳过了
            boolean isSkip = skipOcr(lastPic, gLastPic);

            if (isSkip) {
                res.addAll(buildSameKdaData(lastPic, idxes));
            } else {
                res.addAll(buildNotSameKdaData(recordPath, detailDir, lastPic, idxes));
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

    private List<LoLPicData> buildSameKdaData(LoLPicData lastPic, List<Integer> idxes) {
        // 最后一个跟上一次结果一样，不需要进行额外的ocr，直接跟上次一样
        List<LoLPicData> datas = Lists.newArrayList();
        for (Integer idx : idxes) {
            LoLPicData cur = new LoLPicData(lastPic.getK(), lastPic.getD(), lastPic.getA());
            cur.setTargetIndex(idx);
            datas.add(cur);
        }
        return datas;
    }

    private List<LoLPicData> buildNotSameKdaData(String recordPath, File detailDir, LoLPicData lastPic, List<Integer> idxes) {
        List<LoLPicData> datas = Lists.newArrayList();
        LoLPicData tmp = lastPic.copy();
        for (Integer idx : idxes) {
            // 不一样，这个区间内的每个都进行ocr
            LoLPicData cur = parseKda(genKdaSnapshotPath(recordPath, idx));
            cur.setTargetIndex(idx);

            // 精彩时刻进行击杀细节
            if (highlightOccur(tmp, cur)) {
                doSnapShot(recordPath, VideoFileUtil.genSegName(idx), detailDir, KILL_DETAIL_CORP_EXP);
                cur.setHeroKADetail(parseDetailByVisDet(genKillDetailSnapshotPath(recordPath, idx)));
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

    private List<List<String>> buildMergeFileNames(List<Pair<Integer, Integer>> intervals, Collection<File> files) {
        Map<Integer, String> name2PathMap = files.stream().collect(
                Collectors.toMap(file -> VideoFileUtil.genIndex(file.getName()), File::getAbsolutePath, (a, b) -> b)
        );
        List<List<String>> res = Lists.newArrayList();
        for (Pair<Integer, Integer> interval : intervals) {
            List<String> tmp = Lists.newArrayList();
            for (int i = interval.getLeft(); i < interval.getRight() + 1; i++) {
                if (name2PathMap.containsKey(i)) {
                    tmp.add(name2PathMap.get(i));
                }
            }
            res.add(tmp);
        }
        return res;
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

    private LoLPicData.HeroKillOrAssistDetail parseDetailByVisDet(String detailPath) {
        File snapShotFile = new File(detailPath);
        if (!snapShotFile.exists()) {
            return null;
        }

        MediaType mediaType = MediaType.parse("application/json");
        Map<String, String> params = Maps.newHashMap();
        params.put("path", detailPath);
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

        String resp;
        try {
            resp = OkHttpClientUtil.execute(request);
        } catch (Exception e) {
            log.error("detect kda box error, file: {}.", snapShotFile.getAbsolutePath(), e);
            return Lists.newArrayList();
        }
        JSONArray detectArrays = JSON.parseArray(resp);
        for (Object detectObj : detectArrays) {
            JSONObject detObj = (JSONObject) detectObj;

            List<Integer> boxes = detObj.getJSONArray("boxes").toJavaList(Integer.class);
            String ocrText = detObj.getString("text");
            float score = detObj.getFloat("score");
            if (isValidKadStr(ocrText)) {
                List<List<Integer>> fourPoints = Lists.partition(boxes, 2);
                log.info("find kda boxed success, boxes: {}, text: {}, confidence: {}.",
                        JSON.toJSONString(fourPoints), ocrText, score);
                return fourPoints;
            }
        }
        return Lists.newArrayList();
    }

    private boolean isValidKadStr(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return StringUtils.split(text, "/").length == 3;
    }
}
