package com.sh.engine.plugin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.plugin.lol.LoLPicData;
import com.sh.engine.plugin.lol.LolSequenceStatistic;
import com.sh.engine.service.MsgSendService;
import com.sh.engine.service.VideoMergeService;
import com.sh.engine.util.CommandUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    MsgSendService msgSendService;

    /**
     * 精彩片段的视频片段总数
     */
    private static final int MAX_HIGH_LIGHT_SEG_COUNT = 100;
    private static final int OCR_INTERVAL_NUM = 5;
    /**
     * docker run -it -d --name ppocr -p 8866:8866 paddlepaddle/paddle:2.6.0
     * hub install ch_pp-ocrv3==1.2.0
     * hub serving start -m ch_pp-ocrv3
     */
    private static final String OCR_URL = "http://127.0.0.1:5000/ocr";
    private static final String DETAIL_URL = "http://127.0.0.1:5000/lolKillVisDet";

    private static final Map<String, Integer> LAST_OCR_K_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_D_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_A_MAP = Maps.newConcurrentMap();
    public static final List<Integer> BLANK_KADS = Lists.newArrayList(-1, -1, -1);

    /**
     * kda + 击杀细节 截图位置参数
     */
    private static final String KAD_CORP_EXP = "crop=80:30:in_w*867/1000:0";
    private static final String KILL_DETAIL_CORP_EXP = "crop=270:290:in_w*86/100:in_h*3/16";

    private static final String KAD_SNAPSHOT_FILE = "kda-snapshot";
    private static final String DETAIL_SNAPSHOT_FILE = "detail-snapshot";


    @Override
    public String getPluginName() {
        return "LOL_HL_VOD_CUT";
    }

    @Override
    public boolean process(String recordPath) {
        File highlightFile = new File(recordPath, "highlight.mp4");
        if (highlightFile.exists()) {
            log.info("highlight file already existed, will skip, path: {}", recordPath);
            return true;
        }

        Collection<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false)
                .stream()
                .sorted(Comparator.comparingInt(v -> getIndexFromFileName(v)))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(videos)) {
            log.info("empty ts video file, will skip, path: {}", recordPath);
            return true;
        }

        // 0. 创建文件夹
        createSnapshotFiles(recordPath);

        // 1. 截图
        // 1.1 kda的截图 + detail的截图
        for (File video : videos) {
            String kadSnapshotPath = genSnapshotFilePath(video, KAD_SNAPSHOT_FILE);
            String detailSnapshotPath = genSnapshotFilePath(video, DETAIL_SNAPSHOT_FILE);

            doSnapshot(video, KAD_CORP_EXP, kadSnapshotPath);
            doSnapshot(video, KILL_DETAIL_CORP_EXP, detailSnapshotPath);
        }

        // 1.2 ocr + 位置识别
        List<LoLPicData> datas = parseSeqPicsV2(videos).stream()
                .filter(d -> d.getTargetIndex() != null)
                .collect(Collectors.toList());

        // 3. 找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_SEG_COUNT);
        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();

        // 4. 进行合并视频
        boolean success = videoMergeService.mergeMultiWithFadeV2(buildMergeFileNames(potentialIntervals, videos), highlightFile);
        if (success) {
            msgSendService.send("合并highlight视频完成！路径为：" + highlightFile.getAbsolutePath());
        } else {
            msgSendService.send("合并highlight视频完成！路径为：" + highlightFile.getAbsolutePath());
        }
        return success;
    }

    private void createSnapshotFiles(String recordPath) {
        File snapShotFile = new File(recordPath, KAD_SNAPSHOT_FILE);
        if (!snapShotFile.exists()) {
            snapShotFile.mkdir();
        }

        File detailSnapshotFile = new File(recordPath, DETAIL_SNAPSHOT_FILE);
        if (!detailSnapshotFile.exists()) {
            detailSnapshotFile.mkdir();
        }
    }

    private String genSnapshotFilePath(File segFile, String snapshotFileName) {
        String segFileName = segFile.getName();
        String filePrefix = segFileName.substring(0, segFileName.lastIndexOf("."));

        File picFile = new File(new File(segFile.getParent(), snapshotFileName), filePrefix + ".jpg");
        return picFile.getAbsolutePath();
    }

    private File doSnapshot(File sourceFile, String corpExp, String targetFilePath) {
        File picFile = new File(targetFilePath);
        if (picFile.exists()) {
            log.info("snapshot pic already existed, file: {}", picFile.getAbsolutePath());
            return picFile;
        }

        List<String> params = Lists.newArrayList(
                "-y",
                "-i", sourceFile.getAbsolutePath(),
                "-vf", corpExp,
                "-ss", "00:00:00",
                "-frames:v", "1",
                picFile.getAbsolutePath()
        );
        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
        Integer resCode = CommandUtil.cmdExecWithoutLog(ffmpegCmd);
        if (resCode == 0) {
            log.info("get pic success, path: {}", sourceFile.getAbsolutePath());
            return picFile;
        } else {
            log.info("get pic fail, path: {}", sourceFile.getAbsolutePath());
            return null;
        }
    }

    private List<LoLPicData> parseSeqPicsV2(Collection<File> videos) {
        // 1.构建index到截图的映射
        Map<Integer, String> index2KadShotPath = Maps.newHashMap();
        Map<Integer, String> index2DetailShotPath = Maps.newHashMap();
        List<Integer> indexes = Lists.newArrayList();
        for (File video : videos) {
            Integer index = getIndexFromFileName(video);
            index2KadShotPath.put(index, genSnapshotFilePath(video, KAD_SNAPSHOT_FILE));
            index2DetailShotPath.put(index, genSnapshotFilePath(video, DETAIL_SNAPSHOT_FILE));
            indexes.add(index);
        }

        // 2. 遍历index列表，分析前后kad
        LoLPicData lastPic = LoLPicData.genBlank();
        List<LoLPicData> res = Lists.newArrayList();

        List<List<Integer>> batchIndexes = Lists.partition(indexes, OCR_INTERVAL_NUM);
        for (List<Integer> idxes : batchIndexes) {
            int batchSize = idxes.size();
            Integer testIndex = idxes.get(idxes.size() - 1);

            // 当前批的最后一个进行测试ocr
            LoLPicData gLastPic = testParse(index2KadShotPath.get(testIndex));

            // 前后kda是否一样，一样就可以跳过了
            boolean isSkip = skipOcr(lastPic, gLastPic);

            for (int i = 0; i < batchSize; i++) {
                Integer curIndex = idxes.get(i);
                LoLPicData cur;
                if (isSkip) {
                    // 最后一个跟上一次结果一样，不需要进行额外的ocr，直接跟上次一样
                    cur = new LoLPicData(lastPic.getK(), lastPic.getD(), lastPic.getA());
                } else {
                    // 不一样，这个区间内的每个都进行ocr
                    cur = parseKda(index2KadShotPath.get(curIndex));
                    if (cur.beValid()) {
                        cur.setHeroKADetail(doDetailSnapshotAndParse(lastPic, cur, index2DetailShotPath.get(curIndex)));
                    }
                }
                cur.setTargetIndex(curIndex);
                res.add(cur);
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
        boolean invalid = cur.beInvalid();
        if (invalid) {
            return false;
        }
        return cur.getK() > lastPic.getK() || cur.getA() > lastPic.getA();
    }

    private LoLPicData.HeroKillOrAssistDetail doDetailSnapshotAndParse(LoLPicData lastPic, LoLPicData cur, String path) {
        if (!highlightOccur(lastPic, cur)) {
            return null;
        }
        // kda增加，说明有精彩镜头，需要堆击杀细节进行截图识别
        return parseDetailByVisDet(path);
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

    private static Integer getIndexFromFileName(File file) {
        if (!file.exists()) {
            return null;
        }
        String fileName = file.getName();
        int start = fileName.lastIndexOf("-");
        int end = fileName.lastIndexOf(".");
        return Integer.valueOf(fileName.substring(start + 1, end));
    }

    private List<List<String>> buildMergeFileNames(List<Pair<Integer, Integer>> intervals, Collection<File> files) {
        Map<Integer, String> name2PathMap = files.stream().collect(
                Collectors.toMap(LoLVodHighLightCutPlugin::getIndexFromFileName, File::getAbsolutePath, (a, b) -> b)
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
                .url(OCR_URL)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        String ocrStr = JSON.parseObject(resp).getString("text");
        String score = JSON.parseObject(resp).getString("score");
        if (StringUtils.isBlank(ocrStr)) {
            // 空字符传说明没有kda
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
                .url(DETAIL_URL)
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
}
