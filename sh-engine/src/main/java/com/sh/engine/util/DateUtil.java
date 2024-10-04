package com.sh.engine.util;

import org.apache.commons.lang3.time.DateUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * @author caiWen
 * @date 2023/2/21 22:16
 */
public class DateUtil {
    public static final String YYYY_MM_DD_HH_MM_SS = "yyyy-MM-dd HH:mm:ss";
    public static final String YYYY_MM_DD_HH_MM_SS_V2 = "yyyy-MM-dd-HH-mm-ss";
    public static final String YYYY_MM_DD = "yyyy-MM-dd";

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");
    private static final DateTimeFormatter DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static Date covertStr2Date(String dateStr, String format) {
        Date date;
        try {
            date = DateUtils.parseDate(dateStr, format);
        } catch (ParseException e) {
            date = new Date();
        }
        return date;
    }

    /**
     * 根据给定的时间字符串返回年月日 + 时间段描述
     *
     * @param timeStr 时间字符串
     * @param format 时间格式
     * @return 年月日 + 时间段描述
     */
    public static String describeTime(String timeStr, String format) {
        LocalDateTime dateTime = LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern(format));
        LocalTime time = dateTime.toLocalTime();
        String timeDescription = getTimeDescription(time);
        return dateTime.toLocalDate().format(DATE_ONLY_FORMATTER) + " " + timeDescription;
    }

    /**
     * 获取时间段描述
     *
     * @param time 时间
     * @return 时间段描述
     */
    private static String getTimeDescription(LocalTime time) {
        int hour = time.getHour();
        if (hour < 4) {
            return "凌晨";
        } else if (hour >= 5 && hour < 12) {
            return "早上";
        } else if (hour >= 12 && hour < 18) {
            return "中午";
        } else if (hour >= 18) {
            return "晚上";
        } else {
            return "下午";
        }
    }
}
