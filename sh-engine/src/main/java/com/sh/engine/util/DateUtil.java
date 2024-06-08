package com.sh.engine.util;

import org.apache.commons.lang3.time.DateUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/2/21 22:16
 */
public class DateUtil {
    public static final String YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss";

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

    public static Date covertStr2Date(String dateStr, String format) {
        Date date;
        try {
            date = DateUtils.parseDate(dateStr, format);
        } catch (ParseException e) {
            date = new Date();
        }
        return date;
    }
}
