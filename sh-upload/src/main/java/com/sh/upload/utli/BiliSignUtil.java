package com.sh.upload.utli;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.crypto.digest.MD5;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author caiWen
 * @date 2023/2/26 22:53
 */
public class BiliSignUtil {
    private static final String APPKEY = "aae92bc66f3edfab";
//    private static final String APP_SECRET = "59b43e04ad6965f34319062b478f83dd";
    private static final String APP_SECRET = "af125a0d5279fd576c1b4418a3e8276d";
//    private static final String APP_SECRET = "560c52ccd288fed045859ed18bffd973";
    public static String genSign(Map<String, String> urlParams) {
        List<String> keys = Lists.newArrayList(urlParams.keySet());
        List<String> pushList = Lists.newArrayList();
        Arrays.sort(keys.toArray());
        try {
            for (int i = keys.size() - 1; i >= 0 ; i--) {
                String key = keys.get(i);
                pushList.add(key + "=" + urlParams.get(key));
            }
            String joinStr = StringUtils.join(pushList, "&");
//            return joinStr + APP_SECRET;
            return DigestUtils.md5Hex(joinStr + APP_SECRET);
        } catch (Exception e) {
            return "";
        }
    }


    public static void main(String[] args) {
        Map<String, String> map = Maps.newHashMap();
        map.put("access_key", "38b99704f9393fe15ecd7d38635999c3");
        map.put("build", "2301088");

//        String r = SignedQuery.r(map);
//        String s = SignedQuery.b(r);
//        //        String s = genSign2(map);
        System.out.println(genSign(map));
//        POST /x/vu/client/add?access_key=4faa3abfb2c76c48c5adad38260c5e22&build=2301088&sign=38b99704f9393fe15ecd7d38635999c3 HTTP/1.1
//        POST /x/vu/client/add?access_key=4faa3abfb2c76c48c5adad38260c5e22&build=2301088&sign=38b99704f9393fe15ecd7d38635999c3 HTTP/1.1
//        POST /x/vu/client/edit?access_key=4faa3abfb2c76c48c5adad38260c5e22&sign=38b99704f9393fe15ecd7d38635999c3 HTTP/1.1
//        access_key=4faa3abfb2c76c48c5adad38260c5e22
//        access_key=4faa3abfb2c76c48c5adad38260c5e22&build=2301088
//                access_key=4faa3abfb2c76c48c5adad38260c5e22
    }
}
