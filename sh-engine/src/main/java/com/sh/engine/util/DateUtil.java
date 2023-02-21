package com.sh.engine.util;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/2/21 22:16
 */
public class DateUtil {
    public static String getCurDateDesc() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("HH");
        Integer curHour = Integer.valueOf(dateFormat.format(new Date()));
        if (curHour <= 6) {
            return "凌晨";
        }
        if (curHour <= 12) {
            return "早上";
        }
        if (curHour <= 18) {
            return "下午";
        }
        if (curHour <= 24) {
            return "晚上";
        }
        return "";
    }
}
