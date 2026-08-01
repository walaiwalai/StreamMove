package com.sh.engine.processor.plugin.lol;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.google.common.collect.Lists;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.model.highlight.lol.LoLPicData;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 封装 LOL 截图识别服务的请求和响应解析。
 */
@Component
@Slf4j
public class LolOcrClient {
    private static final String KDA_ENDPOINT = "/ocrDet";
    private static final String KILL_DETAIL_ENDPOINT = "/lolKillVisDet";
    private static final List<Integer> BLANK_KDA = Lists.newArrayList(-1, -1, -1);

    @Value("${ocr.server.host}")
    private String host;
    @Value("${ocr.server.port}")
    private String port;
    @Value("${ocr.server.token}")
    private String token;

    public List<Integer> recognizeKda(File snapshotFile) {
        if (!snapshotFile.exists()) {
            return BLANK_KDA;
        }

        String response = postImage(snapshotFile, KDA_ENDPOINT);
        JSONArray detections = JSON.parseArray(response);
        for (Object detection : detections) {
            JSONObject detectionJson = (JSONObject) detection;
            String text = detectionJson.getString("text");
            if (isValidKdaText(text)) {
                log.info("parse image success, file: {}, res: {}, confidence: {}.",
                        snapshotFile.getAbsolutePath(), text, detectionJson.getString("score"));
                return RegexUtil.getMatchList(text, "\\d+", false).stream()
                        .map(Integer::valueOf)
                        .collect(Collectors.toList());
            }
        }
        return BLANK_KDA;
    }

    public LoLPicData.HeroKillOrAssistDetail recognizeKillDetail(File snapshotFile) {
        if (!snapshotFile.exists()) {
            return null;
        }

        String response = postImage(snapshotFile, KILL_DETAIL_ENDPOINT);
        JSONObject responseJson = JSON.parseObject(response);
        List<List<Float>> boxes = JSON.parseObject(
                responseJson.getString("boxes"),
                new TypeReference<List<List<Float>>>() {
                });
        List<Integer> labelIds = JSON.parseObject(
                responseJson.getString("labelIds"),
                new TypeReference<List<Integer>>() {
                });
        if (CollectionUtils.isEmpty(boxes)) {
            return null;
        }

        log.info("parse detail image success, file: {}, labelIds: {}.",
                snapshotFile.getAbsolutePath(), JSON.toJSONString(labelIds));
        return new LoLPicData.HeroKillOrAssistDetail(boxes, labelIds);
    }

    public List<List<Integer>> detectKdaBox(File snapshotFile) {
        if (!snapshotFile.exists()) {
            return Lists.newArrayList();
        }

        String response = postImage(snapshotFile, KDA_ENDPOINT);
        log.info("detect kda boxes resp, file: {}, res: {}.", snapshotFile.getAbsolutePath(), response);
        JSONArray detections = JSON.parseArray(response);
        for (Object detection : detections) {
            JSONObject detectionJson = (JSONObject) detection;
            String text = detectionJson.getString("text");
            if (!isValidKdaText(text)) {
                continue;
            }

            List<Integer> boxCoordinates = detectionJson.getJSONArray("boxes").toJavaList(Integer.class);
            float score = detectionJson.getFloat("score");
            List<List<Integer>> fourPoints = Lists.partition(boxCoordinates, 2);
            log.info("find kda boxed success, boxes: {}, text: {}, confidence: {}.",
                    JSON.toJSONString(fourPoints), text, score);
            return fourPoints;
        }
        return Lists.newArrayList();
    }

    private String postImage(File image, String endpoint) {
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "image",
                        image.getName(),
                        RequestBody.create(MediaType.parse("image/*"), image)
                ).build();
        Request request = new Request.Builder()
                .url("http://" + host + ":" + port + endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        return OkHttpClientUtil.execute(request);
    }

    private static boolean isValidKdaText(String text) {
        return StringUtils.isNotBlank(text) && StringUtils.split(text, "/").length == 3;
    }
}
