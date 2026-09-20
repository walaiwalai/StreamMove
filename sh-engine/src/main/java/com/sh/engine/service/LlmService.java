package com.sh.engine.service;

import com.sh.engine.model.llm.LlmImageInput;
import com.sh.engine.model.llm.LlmCallResult;
import com.sh.engine.model.llm.LlmImageEditResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmService {

    <T> T chat(String prompt, Class<T> resultType);

    /** 返回可审计的原始响应；实现应覆盖本方法以保留供应商原文。 */
    default <T> LlmCallResult<T> chatWithRaw(String prompt, Class<T> resultType) {
        T result = chat(prompt, resultType);
        return new LlmCallResult<>(String.valueOf(result), result);
    }

    <T> CompletableFuture<T> chatAsync(String prompt, Class<T> resultType);

    /**
     * 使用按提示词标注顺序排列的图片生成结构化视觉理解结果。
     */
    default <T> T analyzeImages(
            String prompt, List<LlmImageInput> images, Class<T> resultType) {
        throw new UnsupportedOperationException("image understanding is not supported");
    }

    /** 返回可审计的视觉模型原始响应。 */
    default <T> LlmCallResult<T> analyzeImagesWithRaw(
            String prompt, List<LlmImageInput> images, Class<T> resultType) {
        T result = analyzeImages(prompt, images, resultType);
        return new LlmCallResult<>(String.valueOf(result), result);
    }

    /**
     * 根据输入图片和提示词编辑图片。具体模型能力由服务实现负责，
     * 不支持图片的实现应抛出明确异常。
     *
     * @param sourceImage 输入图片字节
     * @param prompt 图片编辑提示词
     * @return 编辑后的图片字节
     */
    byte[] editImage(byte[] sourceImage, String prompt);

    /** 返回图片编辑供应商原始响应，便于复盘实际模型调用。 */
    default LlmImageEditResult editImageWithRaw(byte[] sourceImage, String prompt) {
        return new LlmImageEditResult(null, editImage(sourceImage, prompt));
    }
}
