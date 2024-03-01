package com.sh.engine.plugin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.collect.Lists;
import com.sh.config.utils.OkHttpClientUtil;
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
 * @Author caiwen
 * @Date 2024 01 26 22 18
 **/
@Component
@Slf4j
public class LoLVideoHighLightCutPlugin implements VideoProcessPlugin {
    @Resource
    private VideoMergeService videoMergeService;

    private static final int MAX_HIGH_LIGHT_COUNT = 20;
    private static final String OCR_URL = "http://127.0.0.1:8866/predict/chinese_ocr_db_crnn_server";

    @Override
    public String getPluginName() {
        return "LOL_HL_VOD_CUT";
    }

    @Override
    public boolean process(String recordPath) {
        Collection<File> videos = FileUtils.listFiles(new File(recordPath), new String[]{"ts"}, false)
                .stream().sorted(Comparator.comparingInt(v -> getIndexFromFileName(v))).collect(Collectors.toList());

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
        List<LoLPicData> datas = pics.stream()
                .map(this::parse)
                .filter(d -> d.getTargetIndex() != null)
//                .sorted(Comparator.comparingInt(LoLPicData::getTargetIndex))
                .collect(Collectors.toList());

        // 3. 分析KDA找出精彩片段
        LolSequenceStatistic statistic = new LolSequenceStatistic(datas, MAX_HIGH_LIGHT_COUNT);
        List<Pair<Integer, Integer>> potentialIntervals = statistic.getPotentialIntervals();

        // 4. 过滤中间视频文件缺失掉片段

        // 5. 进行合并视频
        return videoMergeService.merge(buildMergeFileNames(potentialIntervals, videos), new File(recordPath, "highlight.mp4"));
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
                "-vf", "crop=100:50:in_w*17/20:0",
                "-ss", "00:00:00",
                "-frames:v", "1",
                picFile.getAbsolutePath()
        );
        FfmpegCmd ffmpegCmd = new FfmpegCmd(StringUtils.join(params, " "));
        Integer resCode = CommandUtil.cmdExecWithoutLog(ffmpegCmd);
        if (resCode == 0) {
            log.info("get pic success, path: {}", segFile.getAbsolutePath());
            return picFile;
        } else {
            log.info("get pic fail, path: {}", segFile.getAbsolutePath());
            return null;
        }
    }

    private LoLPicData parse(File snapShotFile) {
        LoLPicData data = new LoLPicData();
        try {
            data = parseKDAStrLocally(snapShotFile);
        } catch (Exception e) {
            log.error("parse error, file: {}", snapShotFile.getAbsolutePath(), e);
        }

        data.setTargetIndex(getIndexFromFileName(snapShotFile));
        return data;
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

    private List<String> buildMergeFileNames(List<Pair<Integer, Integer>> intervals, Collection<File> files) {
        Set<String> nameSet = files.stream().map(File::getName).collect(Collectors.toSet());
        List<String> res = Lists.newArrayList();
        for (Pair<Integer, Integer> interval : intervals) {
            for (int i = interval.getLeft(); i < interval.getRight() + 1; i++) {
                String fileName = "seg-" + i + ".ts";
                if (nameSet.contains(fileName)) {
                    res.add(fileName);
                }
            }
        }
        return res;
    }

    public LoLPicData parseKDAStrLocally(File snapShotFile) {
        String base64Str = ImageUtil.imageToBase64Str(snapShotFile);
        if (StringUtils.isBlank(base64Str)) {
            return LoLPicData.genInvalid();
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
            return LoLPicData.genInvalid();
        }

        String ocrStr = dataArrayObj.getJSONObject(0).getString("text");
        float confidence = dataArrayObj.getJSONObject(0).getFloat("confidence");
        log.info("parse image success, file: {}, res: {}, confidence: {}.", snapShotFile.getAbsolutePath(),
                ocrStr, confidence);

        if (!ocrStr.contains("/")) {
            // 识别错误了
            return LoLPicData.genInvalid();
        }

        List<Integer> nums = RegexUtil.getMatchList(ocrStr, "\\d+", false).stream()
                .map(Integer::valueOf)
                .collect(Collectors.toList());
        return new LoLPicData(nums.get(0), nums.get(1), nums.get(2));
    }

    public static void main(String[] args) {
        File file = new File("C:\\Users\\caiwen\\Desktop\\download\\TheShy\\2024-01-31-03-31-43\\snapshot\\seg-1671.jpg");
        LoLVideoHighLightCutPlugin plugin = new LoLVideoHighLightCutPlugin();
        plugin.parseKDAStrLocally(file);
    }
}
