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
//    /**
//     * 初始化CryptoJS
//     */
//    private static String cryptoJS = null;

//    private static String initCryptoJS() {
//        if (cryptoJS == null) {
//            StringBuilder sb = new StringBuilder();
//            try {
//                BufferedReader buReader = new BufferedReader(new InputStreamReader(
//                        JavaScriptUtil.class.getClassLoader().getResource("taobao-sign.js").openStream()));
//                String line = null;
//                while ((line = buReader.readLine()) != null) {
//                    sb.append(line);
//                }
//                buReader.close();
//            } catch (IOException e) {
//            }
//            cryptoJS = sb.toString();
//        }
//        return cryptoJS;
//    }

    /**
     * 执行js文件
     *
     * @param jsFile js文件名
     * @param method js的方法名
     * @param params js的参数
     * @return 执行结果
     */
    public static String execJsFromFile(String jsFile, String method, Object... params) {
        String scripts = loadJs(jsFile);
        return execJs(scripts, method, params);
    }

    private static String loadJs(String jsFileName) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader buReader = new BufferedReader(new InputStreamReader(
                    JavaScriptUtil.class.getClassLoader().getResource("javascript/" + jsFileName).openStream()));
            String line = null;
            while ((line = buReader.readLine()) != null) {
                sb.append(line);
            }
            buReader.close();
        } catch (IOException e) {
        }
        return sb.toString();
    }


    private static String execJs(String scripts, String method, Object... params) {
        String back = null;
        ScriptEngineManager sem = new ScriptEngineManager();
        ScriptEngine se = sem.getEngineByName("javascript");
        try {
            se.eval(scripts);
            Invocable inv = (Invocable) se;
            back = (String) inv.invokeFunction(method, params);
        } catch (Exception e) {
            log.error("exec js error", e);
        }
        return back;
    }

    public static void main(String[] args) {
        String scripts = loadJs("taobao-sign.js");
        System.out.println(scripts);
    }
}




