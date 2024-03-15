package com.sh.engine.plugin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.config.utils.VideoFileUtils;
import com.sh.engine.base.StreamerInfoHolder;
import com.sh.engine.model.ffmpeg.FfmpegCmd;
import com.sh.engine.plugin.lol.LoLPicData;
import com.sh.engine.plugin.lol.LolSequenceStatistic;
import com.sh.engine.service.VideoMergeService;
import com.sh.engine.util.CommandUtil;
import com.sh.engine.util.ImageUtil;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.util.*;
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

    private static final int MAX_HIGH_LIGHT_COUNT = 10;
    private static final int OCR_INTERVAL_NUM = 5;
    /**
     * hub serving start -m ch_pp-ocrv3
     */
    private static final String OCR_URL = "http://127.0.0.1:8866/predict/ch_pp-ocrv3";

    private static final Map<String, Integer> LAST_OCR_K_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_D_MAP = Maps.newConcurrentMap();
    private static final Map<String, Integer> LAST_OCR_A_MAP = Maps.newConcurrentMap();
    public static final List<Integer> BLANK_KADS = Lists.newArrayList(-1, -1, -1);

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

        // 1. 截图
        File snapShotFile = new File(recordPath, "snapshot");
        if (!snapShotFile.exists()) {
            snapShotFile.mkdir();
        }

        List<File> pics = Lists.newArrayList();
        for (File video : videos) {
            Optional.ofNullable(snapShot(video)).ifPresent(pics::add);
        }

        // 2. 筛选精彩区间
        List<LoLPicData> datas = ocrSeqPics(pics).stream()
                .filter(d -> d.getTargetIndex() != null)
                .collect(Collectors.toList());

        // 3. 分析KDA找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_COUNT);
        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();

        // 4. 进行合并视频
        List<List<String>> intervals = buildMergeFileNames(potentialIntervals, videos);

        return videoMergeService.mergeMultiWithFadeV2(intervals, highlightFile);
    }

    private File snapShot(File segFile) {
        String segFileName = segFile.getName();
        String filePrefix = segFileName.substring(0, segFileName.lastIndexOf("."));

        File snapShotFile = new File(segFile.getParent(), "snapshot");
        File picFile = new File(snapShotFile, filePrefix + ".jpg");
        if (picFile.exists()) {
            log.info("snapshot pic already existed, file: {}", picFile.getAbsolutePath());
            return picFile;
        }

        List<String> params = Lists.newArrayList(
                "-y",
                "-i", segFile.getAbsolutePath(),
                "-vf", "crop=80:30:in_w*867/1000:0",
                "-ss", "00:00:00",
                "-frames:v", "1",
                picFile.getAbsolutePath()
        );
        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
        Integer resCode = CommandUtil.cmdExec(ffmpegCmd);
        if (resCode == 0) {
            log.info("get pic success, path: {}", segFile.getAbsolutePath());
            return picFile;
        } else {
            log.info("get pic fail, path: {}", segFile.getAbsolutePath());
            return null;
        }
    }

    private List<LoLPicData> ocrSeqPics(List<File> pics) {
        LoLPicData lastPic = LoLPicData.genBlank();
        List<LoLPicData> res = Lists.newArrayList();

        List<List<File>> picGroups = Lists.partition(pics, OCR_INTERVAL_NUM);
        for (List<File> gPics : picGroups) {
            int batchSize = gPics.size();
            LoLPicData gLastPic = testParse(gPics.get(batchSize - 1));
            boolean isSkip = skipOcr(lastPic, gLastPic);

            for (int i = 0; i < batchSize; i++) {
                File gPic = gPics.get(i);
                LoLPicData cur;
                if (isSkip) {
                    // 最后一个跟上一次结果一样，不需要进行额外的ocr，直接跟上次一样
                    cur = new LoLPicData(lastPic.getK(), lastPic.getD(), lastPic.getA());
                } else {
                    // 不一样，这个区间内的每个都进行ocr
                    cur = parse(gPic);
                }
                cur.setTargetIndex(getIndexFromFileName(gPic));
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

    private LoLPicData testParse(File snapShotFile) {
        List<Integer> kad = parseKDAByOCR(snapShotFile);
        if (kad.size() < 3) {
            return LoLPicData.genInvalid();
        } else {
            return new LoLPicData(kad.get(0), kad.get(1), kad.get(2));
        }
    }

    private LoLPicData parse(File snapShotFile) {
        LoLPicData data;
        List<Integer> kad = parseKDAByOCR(snapShotFile);
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
        Map<String, String> name2PathMap = files.stream().collect(Collectors.toMap(File::getName, File::getAbsolutePath, (a, b) -> b));
        List<List<String>> res = Lists.newArrayList();
        for (Pair<Integer, Integer> interval : intervals) {
            List<String> tmp = Lists.newArrayList();
            for (int i = interval.getLeft(); i < interval.getRight() + 1; i++) {
                String fileName2 = VideoFileUtils.genSegName(i);
                if (name2PathMap.containsKey(fileName2)) {
                    tmp.add(name2PathMap.get(fileName2));
                }
            }
            res.add(tmp);
        }
        return res;
    }

    private List<Integer> parseKDAByOCR(File snapShotFile) {
        String base64Str = ImageUtil.imageToBase64Str(snapShotFile);
        if (StringUtils.isBlank(base64Str)) {
            return BLANK_KADS;
        }

        MediaType mediaType = MediaType.parse("application/json");
        String params = "{\"images\":[\"" + base64Str + "\"]}";
        RequestBody body = RequestBody.create(mediaType, params);
        Request request = new Request.Builder()
                .url(OCR_URL)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();
        String resp = OkHttpClientUtil.execute(request);
        JSONArray dataArrayObj = JSON.parseObject(resp).getJSONArray("results")
                .getJSONObject(0).getJSONArray("data");
        if (dataArrayObj.isEmpty()) {
            // 空字符传说明没有kda
            log.info("parse no kad, file: {}.", snapShotFile.getAbsolutePath());
            return BLANK_KADS;
        }

        String ocrStr = dataArrayObj.getJSONObject(0).getString("text");
        float confidence = dataArrayObj.getJSONObject(0).getFloat("confidence");
        log.info("parse image success, file: {}, res: {}, confidence: {}.", snapShotFile.getAbsolutePath(),
                ocrStr, confidence);

        return RegexUtil.getMatchList(ocrStr, "\\d+", false).stream()
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }
}
