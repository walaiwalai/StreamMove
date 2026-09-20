package com.sh.engine.model.danmaku;

import com.alibaba.fastjson.JSON;
import org.junit.Assert;
import org.junit.Test;

public class HighlightEvidenceReferenceTest {

    @Test
    public void shouldParseObjectAndTimestampedTextReferences() {
        String response = "{\"titleEvidence\":[{\"evidenceId\":\"VISION-001\","
                + "\"source\":\"VISION\","
                + "\"startTime\":\"00:01:02\",\"endTime\":\"00:01:03\","
                + "\"text\":\"visible result\"}],\"actionEvidence\":["
                + "\"ASR 00:02:03-00:02:05: spoken action\"],"
                + "\"outcomeEvidence\":[\"OCR 00:03:04: final text\"]}";

        HighlightAnalysisResult result = JSON.parseObject(
                response, HighlightAnalysisResult.class);

        Assert.assertEquals("VISION-001",
                result.getTitleEvidence().get(0).getEvidenceId());
        Assert.assertEquals("VISION", result.getTitleEvidence().get(0).getSource());
        Assert.assertEquals("ASR", result.getActionEvidence().get(0).getSource());
        Assert.assertEquals("00:02:03",
                result.getActionEvidence().get(0).getStartTime());
        Assert.assertEquals("00:02:05",
                result.getActionEvidence().get(0).getEndTime());
        Assert.assertEquals("00:03:04",
                result.getOutcomeEvidence().get(0).getEndTime());
    }

    @Test
    public void shouldKeepMalformedTextVisibleAsUnparsedEvidence() {
        HighlightAnalysisResult result = JSON.parseObject(
                "{\"actionEvidence\":[\"missing timestamp\"]}",
                HighlightAnalysisResult.class);

        HighlightEvidenceReference reference = result.getActionEvidence().get(0);
        Assert.assertEquals("UNPARSED", reference.getSource());
        Assert.assertEquals("missing timestamp", reference.getText());
        Assert.assertNull(reference.getStartTime());
    }
}
