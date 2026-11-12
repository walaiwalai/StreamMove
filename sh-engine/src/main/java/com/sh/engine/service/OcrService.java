package com.sh.engine.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.config.utils.EnvUtil;
import com.sh.engine.task.ComputeTasks;
import javax.annotation.Resource;
import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;
import com.sh.config.utils.OkHttpClientUtil;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 与游戏规则无关的现有 /ocrDet 协议入口。 */
@Service
public class OcrService {
    @Resource private ComputeTasks computeTasks;
    @Value("${ocr.server.host}")
    private String host;
    @Value("${ocr.server.port}")
    private String port;
    @Value("${ocr.server.token:}")
    private String token;

    public List<OcrTextDetection> recognize(byte[] image, String fileName) {
        if (image == null || image.length == 0 || StringUtils.isBlank(fileName)) {
            return Collections.emptyList();
        }
        if (!EnvUtil.isTaskMode()) {
            return computeTasks.recognize(image, fileName);
        }
        Request request = new Request.Builder().url("http://" + host + ":" + port + "/ocrDet")
                .addHeader("Authorization", "Bearer " + token)
                .post(new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("image", fileName, RequestBody.create(MediaType.parse("image/jpeg"), image))
                        .build()).build();
        String response = OkHttpClientUtil.execute(request);
        if (StringUtils.isBlank(response)) {
            throw new StreamerRecordException(ErrorEnum.HTTP_REQUEST_ERROR, "Empty OCR response");
        }
        JSONArray detections = JSON.parseArray(response);
        List<OcrTextDetection> result = new ArrayList<>();
        if (detections != null) {
            for (Object value : detections) {
                JSONObject detection = (JSONObject) value;
                JSONArray boxes = detection.getJSONArray("boxes");
                result.add(new OcrTextDetection(detection.getString("text"), detection.getFloatValue("score"),
                        boxes == null ? Collections.emptyList() : boxes.toJavaList(Integer.class)));
            }
        }
        return result;
    }
}
