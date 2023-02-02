package com.sh.upload.model.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/26 14:05
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BiliPreUploadRespose {
    private Integer OK;
    private String url;
    private String complete;
    private String filename;
}
