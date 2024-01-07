package com.sh.engine.model.ffmpeg;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
/**
 * @author caiWen
 * @date 2023/2/15 21:52
 */
@Slf4j
public class StreamGobbler extends  Thread {
    public static final int LINE_LOG_INTERVAL = 50;
    public static final int MAX_LINE = 8000;
    InputStream is;
    String type;
    OutputStream os;

    public StreamGobbler(InputStream is, String type) {
        this(is, type, null);
    }

    public StreamGobbler(InputStream is, String type, OutputStream redirect) {
        this.is = is;
        this.type = type;
        this.os = redirect;
    }

    @Override
    public void run() {
        int lineNo = 1;
        try {
            PrintWriter pw = null;
            if (os != null) {
                pw = new PrintWriter(os);
            }

            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line = null;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (pw != null) {
                    pw.println(line);
                }
                if (lineNo % LINE_LOG_INTERVAL == 0) {
                    log.info(type + ">>>>" + line);
                }
            }

            if (pw != null) {
                pw.flush();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }
}