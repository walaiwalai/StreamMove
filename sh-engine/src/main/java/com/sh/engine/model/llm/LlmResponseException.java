package com.sh.engine.model.llm;

import com.sh.config.exception.ErrorEnum;
import com.sh.config.exception.StreamerRecordException;

/** 携带供应商原始响应的 LLM 协议异常，供业务审计后继续上抛。 */
public final class LlmResponseException extends StreamerRecordException {
    private final String rawEnvelope;

    public LlmResponseException(String message, String rawEnvelope) {
        super(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message);
        this.rawEnvelope = rawEnvelope;
    }

    public LlmResponseException(
            String message, String rawEnvelope, Throwable cause) {
        super(ErrorEnum.HIGHLIGHT_ANALYSIS_ERROR, message, cause);
        this.rawEnvelope = rawEnvelope;
    }

    public String getRawEnvelope() {
        return rawEnvelope;
    }
}
