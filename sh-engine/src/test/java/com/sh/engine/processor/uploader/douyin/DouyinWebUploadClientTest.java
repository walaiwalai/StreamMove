package com.sh.engine.processor.uploader.douyin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sh.engine.processor.uploader.meta.DouyinWorkMetaData;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class DouyinWebUploadClientTest {

    @Test
    public void buildsCapturedPublicTitleDescriptionAndHashtags() {
        DouyinWorkMetaData metadata = new DouyinWorkMetaData();
        metadata.setTitle("StreamerRecord公开发布联调");
        metadata.setDesc("精彩剪辑");
        metadata.setTags(Arrays.asList("游戏", "精彩剪辑"));

        JSONObject common = DouyinWebUploadClient.buildPublishCommon(metadata, "test-video-id");

        assertEquals("StreamerRecord公开发布联调", common.getString("item_title"));
        assertEquals("精彩剪辑 #游戏 #精彩剪辑", common.getString("caption"));
        assertEquals("StreamerRecord公开发布联调 精彩剪辑 #游戏 #精彩剪辑",
                common.getString("text"));
        assertEquals(0, common.getIntValue("visibility_type"));
        assertEquals("search/search", common.getString("hashtag_source"));

        JSONArray textExtra = JSON.parseArray(common.getString("text_extra"));
        assertEquals(2, textExtra.size());
        assertHashtag(textExtra.getJSONObject(0), "游戏", 26, 29, 5, 8);
        assertHashtag(textExtra.getJSONObject(1), "精彩剪辑", 30, 35, 9, 14);
    }

    @Test
    public void omitsHashtagMetadataWhenTagsAreBlank() {
        DouyinWorkMetaData metadata = new DouyinWorkMetaData();
        metadata.setTitle("标题");
        metadata.setDesc("描述");
        metadata.setTags(Arrays.asList("", "#", "  "));

        JSONObject common = DouyinWebUploadClient.buildPublishCommon(metadata, "test-video-id");

        assertEquals("标题 描述", common.getString("text"));
        assertEquals("描述", common.getString("caption"));
        assertEquals("[]", common.getString("text_extra"));
        assertEquals("", common.getString("hashtag_source"));
    }

    private static void assertHashtag(JSONObject hashtag,
                                      String name,
                                      int start,
                                      int end,
                                      int captionStart,
                                      int captionEnd) {
        assertEquals(name, hashtag.getString("hashtag_name"));
        assertEquals(start, hashtag.getIntValue("start"));
        assertEquals(end, hashtag.getIntValue("end"));
        assertEquals(captionStart, hashtag.getIntValue("caption_start"));
        assertEquals(captionEnd, hashtag.getIntValue("caption_end"));
        assertEquals(1, hashtag.getIntValue("type"));
        assertEquals(0, hashtag.getIntValue("hashtag_id"));
        assertEquals("", hashtag.getString("user_id"));
    }
}
