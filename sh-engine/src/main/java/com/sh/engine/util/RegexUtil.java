package com.sh.engine.util;

import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author caiWen
 * @date 2023/1/23 23:18
 */
public final class RegexUtil {
    /**
     * 获得匹配正则表达式的内容
     * @param str 字符串
     * @param reg 正则表达式
     * @param isCaseInsensitive 是否忽略大小写，true忽略大小写，false大小写敏感
     * @return 匹配正则表达式的字符串，组成的List
     */
    public static List<String> getMatchList(final String str, final String reg, final boolean isCaseInsensitive) {
        ArrayList<String> result = new ArrayList<String>();
        Pattern pattern = null;
        if (isCaseInsensitive) {
            pattern = Pattern.compile(reg, Pattern.CASE_INSENSITIVE);
        } else {
            pattern = Pattern.compile(reg);
        }
        Matcher matcher = pattern.matcher(str);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        result.trimToSize();
        return result;
    }

    public static String fetchMatchedOne(final String str, final String reg) {
        Pattern pattern = Pattern.compile(reg);;
        Matcher matcher = pattern.matcher(str);
        while (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
