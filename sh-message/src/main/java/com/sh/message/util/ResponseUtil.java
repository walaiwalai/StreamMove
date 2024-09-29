package com.sh.message.util;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

/**
 * @Author caiwen
 * @Date 2023 07 29 16 28
 **/
@Slf4j
public class ResponseUtil {
    public static String readText(HttpServletRequest request) {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(request.getInputStream()));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            br.close();
            return sb.toString();
        } catch (IOException e) {
            log.error("error to read text in request", e);
        }
        return "";
    }

    public static void writeText(HttpServletResponse response, String text) {
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(response.getOutputStream());
            out.write(text.getBytes());
            out.flush();
        } catch (IOException e) {
            log.error("error to flush file data to response");
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception e) {
                log.error("error to read file");
            }
        }
    }
}
