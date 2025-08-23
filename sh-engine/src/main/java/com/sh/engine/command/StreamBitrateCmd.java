package com.sh.engine.command;

import lombok.extern.slf4j.Slf4j;

/**
 * @Author caiwen
 * @Date 2025 08 17 09 49
 **/
@Slf4j
public class StreamBitrateCmd extends AbstractCmd {
    private final StringBuilder infoOutSb = new StringBuilder();

    /**
     * 码率 kb/s
     */
    private int kbitrate;

    public StreamBitrateCmd(String streamUrl) {
        super("ffprobe -v error -select_streams v:0 -show_entries stream=bit_rate -of default=noprint_wrappers=1:nokey=1 -i \"" + streamUrl + "\"");
    }

    @Override
    protected void processOutputLine(String line) {
        infoOutSb.append(line);
    }

    @Override
    protected void processErrorLine(String line) {

    }

    public void execute(long timeoutSeconds) {
        super.execute(timeoutSeconds);

        String bitrateStr = infoOutSb.toString().trim();
        this.kbitrate = (int) Long.parseLong(bitrateStr) / 1000;
    }

    public int getKbitrate() {
        return kbitrate;
    }


    public static void main(String[] args) {
        StreamBitrateCmd streamBitrateCmd = new StreamBitrateCmd("https://feelsbadman.eu.srcdn.net/ddl/3f98d8b3-cdf0-4b81-a9ec-b3aa5fa6a544/pandalive_cherrybest_2025-08-16%2011-07-24_720p_%EB%A7%88%EC%A7%80%EB%AF%B9%20%EB%B0%A9%EC%86%A1%EC%B2%98%EB%9F%BC%20%EC%97%B4%EC%8B%AC%ED%9E%88%20%ED%95%98%EA%B2%A0%EC%8A%B5%EB%8B%88%EB%8B%B9.mp4?iv=JRXwkEdr3Uh2lrOb0dC_6A&enc=b887P_0Vpd7Fu-lcuSvMVBMmegkXL86Cs1H0G7mZnzhypsF_1C8XvkMCVnF-yBFoidBKglXgQXPvfiHGaqV2M8cWSdpxFauJqxc6hqvSdcwxhb5ds6pUDbprkwHgYMphQdxUfblQCCUelIj_TdSn21A7k2d1_YayA-j29aoxBUJySkBKkORKFZwzSdgwjIRsj5E-Blf5zRIdW8SNZZ8tkZj8n_XGhNlYbPR5rqVGRZKKgplHlhJyk_pcPcsJaSVY8XrYto70srm-n2tcA_Cxr1y-uoBLTtt3K0uca11spD5UqV99U-DOQBuLzzX-Pzt9O0T6FAjx5x7_tny75VbEzo4ToEQRdvPdS_7jmD7mKZ3f8viEvZkl47YOQXOBUcNLZ6MPNsnZb_sXUhYUPr7MfCk8a45INWhRExlf_8hhtjrnol3EPio_bVNDEW17rSPu4CNPBA0gZLZWSzo2FL11u5FXLq7xjsBpyZjt3Anb8x0ANqSDNs1T3U4jf3JHe5RA2pTEV2U1QWXZuGBcFxkZ3-FI6QjRHgfxUbLDBta4Y-yVumoKDXNDrKSCaghKm4a6tI6OSi50YMMrH2jIkDLCsA");
        streamBitrateCmd.execute(10);
        System.out.println(streamBitrateCmd.kbitrate);
    }
}
