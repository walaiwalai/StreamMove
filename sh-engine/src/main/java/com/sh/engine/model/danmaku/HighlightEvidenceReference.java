package com.sh.engine.model.danmaku;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONType;
import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured reference to a primary evidence item returned by the LLM.
 */
@Data
@JSONType(deserializer = HighlightEvidenceReferenceDeserializer.class)
public class HighlightEvidenceReference {
    private static final String UNPARSED_SOURCE = "UNPARSED";
    private static final Pattern TEXT_REFERENCE_PATTERN = Pattern.compile(
            "^(ASR|OCR|VISION)\\s+(\\d{2}:\\d{2}:\\d{2})"
                    + "(?:\\s*[-–]\\s*(\\d{2}:\\d{2}:\\d{2}))?"
                    + "\\s*[:：]\\s*(.+)$");

    /** Stable ID copied from the evidence catalog, for example ASR-001. */
    private String evidenceId;

    /** Evidence source. Supported values are ASR, OCR and VISION. */
    private String source;

    /** Inclusive evidence start time in HH:mm:ss. */
    private String startTime;

    /** Inclusive evidence end time in HH:mm:ss. */
    private String endTime;

    /** Verbatim evidence text from the supplied timeline. */
    private String text;

    static HighlightEvidenceReference fromJsonValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject) {
            return fromJsonObject((JSONObject) value);
        }
        if (value instanceof String) {
            return fromText((String) value);
        }
        throw new IllegalArgumentException(
                "unsupported highlight evidence reference type: "
                        + value.getClass().getName());
    }

    private static HighlightEvidenceReference fromJsonObject(JSONObject value) {
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        reference.setEvidenceId(value.getString("evidenceId"));
        reference.setSource(value.getString("source"));
        reference.setStartTime(value.getString("startTime"));
        reference.setEndTime(value.getString("endTime"));
        reference.setText(value.getString("text"));
        return reference;
    }

    private static HighlightEvidenceReference fromText(String value) {
        String normalized = value == null ? "" : value.trim();
        Matcher matcher = TEXT_REFERENCE_PATTERN.matcher(normalized);
        HighlightEvidenceReference reference = new HighlightEvidenceReference();
        if (!matcher.matches()) {
            reference.setSource(UNPARSED_SOURCE);
            reference.setText(normalized);
            return reference;
        }
        reference.setSource(matcher.group(1));
        reference.setStartTime(matcher.group(2));
        reference.setEndTime(matcher.group(3) == null
                ? matcher.group(2) : matcher.group(3));
        reference.setText(matcher.group(4));
        return reference;
    }
}
