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
            return "midnight";
        }
        if (curHour <= 12) {
            return "morning";
        }
        if (curHour <= 18) {
            return "afternoon";
        }
        if (curHour <= 24) {
            return "evening";
        }
        return "";
    }
}
