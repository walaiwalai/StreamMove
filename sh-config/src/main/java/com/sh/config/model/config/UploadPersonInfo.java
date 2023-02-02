package com.sh.config.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author caiWen
 * @date 2023/1/23 10:42
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UploadPersonInfo {
    private String biliCookies;
    private String nickname;
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private Long tokenSignDate;
    private Long mid;
}
