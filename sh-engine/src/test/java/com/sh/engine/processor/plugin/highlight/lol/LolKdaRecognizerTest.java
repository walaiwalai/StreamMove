package com.sh.engine.processor.plugin.highlight.lol;

import com.sh.engine.model.highlight.core.OcrTextDetection;
import com.sh.engine.processor.plugin.highlight.HighlightOcrClient;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LolKdaRecognizerTest {
    @Test
    public void shouldParseKdaAndBoxFromMemoryFrame() throws Exception {
        HighlightOcrClient ocrClient = new HighlightOcrClient() {
            @Override
            public List<OcrTextDetection> recognize(byte[] jpegData, String fileName) {
                return Collections.singletonList(new OcrTextDetection(
                        "12/3/4", 0.95f, Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8)));
            }
        };
        LolKdaRecognizer recognizer = new LolKdaRecognizer();
        Field ocrClientField = LolKdaRecognizer.class.getDeclaredField("ocrClient");
        ocrClientField.setAccessible(true);
        ocrClientField.set(recognizer, ocrClient);

        byte[] jpegData = new byte[]{1, 2, 3};
        Assert.assertEquals(
                Arrays.asList(12, 3, 4), recognizer.recognizeKda(jpegData, "kda.jpg"));
        Assert.assertEquals(
                Arrays.asList(
                        Arrays.asList(1, 2),
                        Arrays.asList(3, 4),
                        Arrays.asList(5, 6),
                        Arrays.asList(7, 8)),
                recognizer.detectKdaBox(jpegData, "kda.jpg"));
    }
}
