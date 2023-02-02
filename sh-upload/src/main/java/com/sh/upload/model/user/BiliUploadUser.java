package com.sh.upload.model.user;

import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.digest.MD5;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sh.upload.constant.UploadConstant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author caiWen
 * @date 2023/1/25 18:13
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Slf4j
public class BiliUploadUser {
    private String accessToken;
    private Long mid;
    private String sessionId;
    private String jseSessionId;
    private String refreshToken;
    private Long expiresIn;
    private String nickName;
    private Long tokenSignTime;


    public void checkToken() {
        String url = UploadConstant.BILI_TOKEN_CHECK_URL_PREFIX + accessToken;
        String resp = HttpUtil.get(url);
        System.out.println(resp);
    }

    public static void main(String[] args) throws UnsupportedEncodingException {
        BiliUploadUser biliUploadUser = new BiliUploadUser();
//        appKey:1d8b6e7d45233436
        biliUploadUser.setAccessToken("73e93b10ad24b367db3d15e1aa96b012");
//        biliUploadUser.checkToken();

//app.bilibili.com/x/v2/account/mine?access_key=73e93b10ad24b367db3d15e1aa96b012&appkey=1d8b6e7d45233436
// &bili_link_new=1&build=6660400&c_locale=zh_CN&channel=xiaomi&disable_rcmd=0&mobi_app=android&platform=android
// &s_locale=zh_CN&statistics=%7B%22appId%22%3A1%2C%22platform%22%3A3%2C%22version%22%3A%226.66
// .0%22%2C%22abtest%22%3A%22%22%7D&ts=1674647009&sign=d149624cf3f7032c6c7b856c828922ee

//        "?access_key=73e93b10ad24b367db3d15e1aa96b012&appkey=1d8b6e7d45233436&bili_link_new=0&build=6660400
//        &c_locale" +
//                "=zh_CN&channel=xiaomi&disable_rcmd=0&mobi_app=android&platform=android&s_locale=zh_CN&statistics
//                =%7B" +
//                "%22appId%22%3A1%2C%22platform%22%3A3%2C%22version%22%3A%226.66" +
//                ".0%22%2C%22abtest%22%3A%22%22%7D&ts=1674647009";

        Map<String, String> params = Maps.newHashMap();
        params.put("access_key", "73e93b10ad24b367db3d15e1aa96b012");
        params.put("appkey", "1d8b6e7d45233436");
        params.put("bili_link_new", "1");
        params.put("build", "6660400");
        params.put("c_locale", "zh_CN");
        params.put("channel", "xiaomi");
        params.put("disable_rcmd", "0");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("s_locale", "zh_CN");
        params.put("statistics", "%7B%22appId%22%3A1%2C%22platform%22%3A3%2C%22version%22%3A%226.66.0%22%2C%22abtest%22%3A%22%22%7D");
        params.put("ts", "1674647009");

        String[] keys = Lists.newArrayList(params.keySet()).toArray(new String[0]);
        List<String> tmps = Arrays.stream(keys).sorted(Comparator.reverseOrder()).collect(Collectors.toList());

        System.out.println(JSON.toJSON(tmps));
        List<String> res = Lists.newArrayList();
        for (String tmp : tmps) {
            String enCodeStr = URLEncoder.encode(params.get(tmp), "UTF-8");
//            if (StringUtils.equals(tmp, "statistics")) {
//                enCodeStr = params.get(tmp);
//            }
//            String enCodeStr = params.get(tmp);
            res.add(tmp + "=" + enCodeStr);
        }

        String appSecret = "59b43e04ad6965f34319062b478f83dd";
        System.out.println(StringUtils.join(res, "&"));
        String s = StringUtils.join(res, "&") + appSecret;
        String ans = DigestUtils.md5Hex(s);

        System.out.println(ans);

//        for (const key of keys) {
//            post_list.push(`${key}=${encodeURIComponent(post_data[key])}`)
//            // 转义数字必须大写
//        }
//        return `${post_list.join("&")}${APPSECRET}`
    }
}
