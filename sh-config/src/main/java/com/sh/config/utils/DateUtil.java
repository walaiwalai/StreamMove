package com.sh.config.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
    public static final String HH = "HH";

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

    public static String covertTimeStampToStr(Long timeStamp) {
        return LocalDateTime.ofEpochSecond(timeStamp / 1000, 0, ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern(YYYY_MM_DD_HH_MM_SS_V2));
    }

    /**
     * 根据给定的时间字符串返回年月日 + 时间段描述
     *
     * @param timeStr 时间字符串
     * @param format  时间格式
     * @return 年月日 + 时间段描述
     */
    public static String describeTime(String timeStr, String format) {
        LocalDateTime dateTime = LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern(format));
        LocalTime time = dateTime.toLocalTime();
        String timeDescription = getTimeDescription(time);
        return dateTime.toLocalDate().format(DATE_ONLY_FORMATTER) + " " + timeDescription;
    }

    /**
     * 根据给定的时间字符串
     *
     * @param date   时间
     * @param format 时间格式
     * @return 年月日
     */
    public static String formatTime(Date date, String format) {
        if (date == null || StringUtils.isBlank(format)) {
            return null;
        }
        return new SimpleDateFormat(format).format(date);
    }

    /**
     * 获取时间段描述
     *
     * @param time 时间
     * @return 时间段描述
     */
    private static String getTimeDescription(LocalTime time) {
        int hour = time.getHour();
        String prefix = "";
        if (hour < 5) {
            prefix = "凌晨";
        } else if (hour < 11) {
            prefix = "早上";
        } else if (hour < 13) {
            prefix = "中午";
        } else if (hour < 18) {
            prefix = "下午";
        } else {
            prefix = "晚上";
        }

        return prefix + hour + "点";
    }
}
