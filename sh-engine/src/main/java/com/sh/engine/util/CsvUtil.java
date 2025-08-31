package com.sh.engine.util;

import com.sh.engine.model.buffer.CsvItem;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author caiwen
 * @Date 2025 08 30 10 20
 **/
@Slf4j
public class CsvUtil {
    /**
     * 读取CSV文件，支持泛型
     */
    @SuppressWarnings("unchecked")
    public static <T extends CsvItem> List<T> readCsvItems(String filePath, Class<T> clazz) {
        List<T> items = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            T t = clazz.getDeclaredConstructor().newInstance();
            String line;
            // 跳过表头
            br.readLine();

            while ((line = br.readLine()) != null) {
                T item = (T) t.covertItem(line);
                items.add(item);
            }
        } catch (Exception e) {
            log.error("read csv error, file: {}", filePath, e);
        }

        return items;
    }

    /**
     * 转义CSV中的特殊字符
     */
    public static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }

        // 如果包含逗号、引号或换行符，需要用双引号包裹
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            // 替换双引号为两个双引号
            value = value.replace("\"", "\"\"");
            return "\"" + value + "\"";
        }

        return value;
    }
}
