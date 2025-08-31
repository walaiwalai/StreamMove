package com.sh.engine.model.buffer;

/**
 * CSV行对象
 *
 * @Author caiwen
 * @Date 2025 08 30 10 04
 **/
public interface CsvItem {
    /**
     * 生成CSV表头
     *
     * @return csv表头
     */
    String genHeader();

    /**
     * 将对象转换为CSV行
     *
     * @return 单行信息
     */
    String genLine();


    /**
     * 将CSV行转换为对象
     *
     * @param line csv行
     * @return 对象
     */
    CsvItem covertItem(String line);
}
