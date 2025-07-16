package com.sh.engine.processor.uploader.meta;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author : caiwen
 * @Date: 2024/9/30
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class BiliWorkMetaData extends WorkMetaData {
    /**
     * 投稿分区
     */
    private Integer tid;

    /**
     * 视频封面url
     */
    private String cover;

    /**
     * 视频来源描述
     */
    private String source;
}
