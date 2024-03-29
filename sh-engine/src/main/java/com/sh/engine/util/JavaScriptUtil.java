package com.sh.engine.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * @author caiWen
 * @date 2023/2/5 13:39
 */
@Slf4j
public class JavaScriptUtil {
    /**
     * 初始化CryptoJS
     */
    private static String cryptoJS = null;

    private static String initCryptoJS() {
        if (cryptoJS == null) {
            StringBuilder sb = new StringBuilder();
            try {
                BufferedReader buReader = new BufferedReader(new InputStreamReader(
                        JavaScriptUtil.class.getClassLoader().getResource("crypto-js.min.js").openStream()));
                String line = null;
                while ((line = buReader.readLine()) != null) {
                    sb.append(line);
                }
                buReader.close();
            } catch (IOException e) {
            }
            cryptoJS = sb.toString();
        }
        return cryptoJS;
    }

    public static String execJs(String scripts, String method, Object... params) {
        String back = null;
        ScriptEngineManager sem = new ScriptEngineManager();
        ScriptEngine se = sem.getEngineByName("javascript");
        try {
            se.eval(scripts);
            se.eval(initCryptoJS());
            Invocable inv = (Invocable) se;
            back = (String) inv.invokeFunction(method, params);
        } catch (Exception e) {
            log.error("exec js error", e);
        }
        return back;
    }


    public static String execJsByFileName(String jsFilePath, String functionName, Object... params) {
        // 获取JavaScript引擎
        ScriptEngineManager sem = new ScriptEngineManager();
        ScriptEngine engine = sem.getEngineByName("nashorn");
        String result = null;
        try {
            // 读取JavaScript文件内容
            ClassPathResource classPathResource = new ClassPathResource("js/" + jsFilePath);
            String scriptContent = IOUtils.toString(classPathResource.getInputStream(), StandardCharsets.UTF_8);

            // 执行JavaScript代码
            engine.eval(scriptContent);

            // 调用指定函数
            Invocable invocable = (Invocable) engine;
            result = (String) invocable.invokeFunction(functionName, params);
            return result;
        } catch (Exception e) {
            log.error("exec js error", e);
        }

        return null;
    }
}




