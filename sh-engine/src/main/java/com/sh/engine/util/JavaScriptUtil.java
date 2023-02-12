package com.sh.engine.util;

import lombok.extern.slf4j.Slf4j;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

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
}




