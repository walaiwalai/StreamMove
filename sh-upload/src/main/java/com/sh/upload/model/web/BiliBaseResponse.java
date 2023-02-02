package com.sh.upload.model.web;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @author caiWen
 * @date 2023/1/25 19:11
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BiliBaseResponse<T> {
    private int code;
    private String message;
    private T data;
}
