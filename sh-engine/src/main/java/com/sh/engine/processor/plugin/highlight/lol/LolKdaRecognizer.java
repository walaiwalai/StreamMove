package com.sh.engine.processor.plugin.highlight.lol;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import com.sh.engine.util.RegexUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 将通用 OCR 文字框解释为 LoL K/D/A 数值或 KDA 画面坐标。
 */
@Component
@Slf4j
public class LolKdaRecognizer {
    private static final List<Integer> BLANK_KDA = Collections.unmodifiableList(
            Arrays.asList(-1, -1, -1));

    @Resource
    private HighlightOcrClient ocrClient;

    /**
     * 识别内存 JPEG 中的 K/D/A。
     *
     * @param jpegData JPEG 字节
     * @param fileName 仅用于 OCR 服务和日志定位的文件名
     * @return K/D/A 三个整数；未识别时返回 -1/-1/-1
     */
    public List<Integer> recognizeKda(byte[] jpegData, String fileName) {
        if (jpegData == null || jpegData.length == 0 || StringUtils.isBlank(fileName)) {
            return BLANK_KDA;
        }
        List<OcrTextDetection> detections = ocrClient.recognize(jpegData, fileName);
        for (OcrTextDetection detection : detections) {
            String text = detection.getText();
            if (isValidKdaText(text)) {
                log.info("parse KDA success, image: {}, result: {}, confidence: {}",
                        fileName, text, detection.getScore());
                return RegexUtil.getMatchList(text, "\\d+", false).stream()
                        .map(Integer::valueOf)
                        .collect(Collectors.toList());
            }
        }
        return BLANK_KDA;
    }

    /**
     * 返回内存 JPEG 中首个合法 K/D/A 文本对应的四点坐标。
     *
     * @param jpegData JPEG 字节
     * @param fileName 仅用于 OCR 服务和日志定位的文件名
     * @return 四点坐标；未识别时返回空集合
     */
    public List<List<Integer>> detectKdaBox(byte[] jpegData, String fileName) {
        if (jpegData == null || jpegData.length == 0 || StringUtils.isBlank(fileName)) {
            return Collections.emptyList();
        }
        List<OcrTextDetection> detections = ocrClient.recognize(jpegData, fileName);
        for (OcrTextDetection detection : detections) {
            if (!isValidKdaText(detection.getText()) || detection.getBoxes().size() != 8) {
                continue;
            }
            List<List<Integer>> fourPoints = Lists.partition(detection.getBoxes(), 2);
            log.info("find KDA box success, image: {}, boxes: {}, text: {}, confidence: {}",
                    fileName, JSON.toJSONString(fourPoints),
                    detection.getText(), detection.getScore());
            return fourPoints;
        }
        return Collections.emptyList();
    }

    private boolean isValidKdaText(String text) {
        return StringUtils.isNotBlank(text)
                && StringUtils.split(text, "/").length == 3
                && RegexUtil.getMatchList(text, "\\d+", false).size() == 3;
    }
}
