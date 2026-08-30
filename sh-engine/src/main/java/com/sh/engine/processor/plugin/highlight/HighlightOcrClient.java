package com.sh.engine.processor.plugin.highlight;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.model.highlight.lol.LoLPicData;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 高光识别服务客户端，统一接收内存 JPEG 并封装文字 OCR 与 LoL 击杀详情接口。
 */
@Component
@Slf4j
public class HighlightOcrClient {
    private static final String OCR_ENDPOINT = "/ocrDet";
    private static final String KILL_DETAIL_ENDPOINT = "/lolKillVisDet";

    @Value("${ocr.server.host}")
    private String host;
    @Value("${ocr.server.port}")
    private String port;
    @Value("${ocr.server.token:}")
    private String token;

    /**
     * 识别内存中的 JPEG 文字。
     *
     * @param jpegData JPEG 字节
     * @param fileName multipart 文件名，仅用于服务端诊断
     * @return OCR 文字框
     */
    public List<OcrTextDetection> recognize(byte[] jpegData, String fileName) {
        if (jpegData == null || jpegData.length == 0 || StringUtils.isBlank(fileName)) {
            return Collections.emptyList();
        }
        String response = postImage(
                RequestBody.create(MediaType.parse("image/jpeg"), jpegData),
                fileName,
                OCR_ENDPOINT);
        return parseOcrResponse(response, fileName);
    }

    private List<OcrTextDetection> parseOcrResponse(String response, String imageDescription) {
        if (StringUtils.isBlank(response)) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "empty OCR response for " + imageDescription);
        }

        JSONArray detections = JSON.parseArray(response);
        List<OcrTextDetection> result = new ArrayList<>();
        if (detections == null) {
            return result;
        }
        for (Object item : detections) {
            JSONObject detection = (JSONObject) item;
            JSONArray boxes = detection.getJSONArray("boxes");
            result.add(new OcrTextDetection(
                    detection.getString("text"),
                    detection.getFloatValue("score"),
                    boxes == null ? Collections.emptyList() : boxes.toJavaList(Integer.class)));
        }
        return result;
    }

    /**
     * 识别内存中的 LoL 击杀详情图。
     *
     * @param jpegData JPEG 字节
     * @param fileName multipart 文件名，仅用于服务端诊断
     * @return 击杀或助攻详情；未检测到目标时返回 null
     */
    public LoLPicData.HeroKillOrAssistDetail recognizeKillDetail(
            byte[] jpegData,
            String fileName) {
        if (jpegData == null || jpegData.length == 0 || StringUtils.isBlank(fileName)) {
            return null;
        }
        String response = postImage(
                RequestBody.create(MediaType.parse("image/jpeg"), jpegData),
                fileName,
                KILL_DETAIL_ENDPOINT);
        return parseKillDetailResponse(response, fileName);
    }

    private LoLPicData.HeroKillOrAssistDetail parseKillDetailResponse(
            String response,
            String imageDescription) {
        if (StringUtils.isBlank(response)) {
            throw new StreamerRecordException(
                    ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR,
                    "empty kill detail response for " + imageDescription);
        }
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

        log.info("parse detail image success, image: {}, labelIds: {}.",
                imageDescription, JSON.toJSONString(labelIds));
        return new LoLPicData.HeroKillOrAssistDetail(boxes, labelIds);
    }

    private String postImage(RequestBody imageBody, String fileName, String endpoint) {
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, imageBody)
                .build();
        Request request = new Request.Builder()
                .url("http://" + host + ":" + port + endpoint)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        return OkHttpClientUtil.execute(request);
    }
}
