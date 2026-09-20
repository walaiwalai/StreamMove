package com.sh.engine.model.danmaku;

import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.JSONToken;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;

import java.lang.reflect.Type;

/**
 * 兼容模型将结构化证据偶发返回为带来源和时间戳的单行文本。
 */
public class HighlightEvidenceReferenceDeserializer implements ObjectDeserializer {

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialze(DefaultJSONParser parser, Type type, Object fieldName) {
        Object value = parser.parse();
        return (T) HighlightEvidenceReference.fromJsonValue(value);
    }

    @Override
    public int getFastMatchToken() {
        return JSONToken.LBRACE;
    }
}
