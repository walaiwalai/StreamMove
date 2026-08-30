package com.sh.engine.service.impl;

import com.sh.engine.model.highlight.core.HighlightMask;
import com.sh.engine.model.highlight.core.HighlightMaskPlan;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VideoMergeServiceImplMaskTest {

    @Test
    public void shouldBuildBlurAndDarkenFilterForEachVideoInput() throws Exception {
        VideoMergeServiceImpl service = new VideoMergeServiceImpl();
        HighlightMaskPlan plan = new HighlightMaskPlan(Collections.singletonList(
                new HighlightMask(0.1, 0.8, 0.2, 0.1)));
        StringBuilder filter = new StringBuilder();

        Method method = VideoMergeServiceImpl.class.getDeclaredMethod(
                "appendMaskFilters", StringBuilder.class, int.class,
                HighlightMaskPlan.class, int.class, int.class);
        method.setAccessible(true);
        String outputLabel = (String) method.invoke(service, filter, 2, plan, 1920, 1080);

        assertEquals("mask2_0out", outputLabel);
        assertTrue(filter.toString().contains("[2:v]split=2"));
        assertTrue(filter.toString().contains("crop=384:108:192:864"));
        assertTrue(filter.toString().contains("boxblur=luma_radius=20"));
        assertTrue(filter.toString().contains("drawbox=x=192:y=864:w=384:h=108"));
    }
}
