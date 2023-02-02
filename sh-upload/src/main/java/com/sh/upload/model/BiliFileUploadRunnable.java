//package com.sh.upload.model;
//
//import cn.hutool.crypto.digest.MD5;
//import lombok.Data;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.lang3.time.DateUtils;
//import org.apache.http.Consts;
//import org.apache.http.HttpEntity;
//import org.apache.http.client.CookieStore;
//import org.apache.http.client.HttpClient;
//import org.apache.http.client.entity.UrlEncodedFormEntity;
//import org.apache.http.client.methods.CloseableHttpResponse;
//import org.apache.http.client.methods.HttpPost;
//import org.apache.http.client.methods.HttpPut;
//import org.apache.http.entity.BufferedHttpEntity;
//import org.apache.http.entity.ContentType;
//import org.apache.http.entity.InputStreamEntity;
//import org.apache.http.entity.mime.FormBodyPartBuilder;
//import org.apache.http.entity.mime.Header;
//import org.apache.http.entity.mime.HttpMultipartMode;
//import org.apache.http.entity.mime.MultipartEntityBuilder;
//import org.apache.http.entity.mime.content.FileBody;
//import org.apache.http.entity.mime.content.InputStreamBody;
//import org.apache.http.entity.mime.content.StringBody;
//import org.apache.http.impl.client.BasicCookieStore;
//import org.apache.http.impl.client.CloseableHttpClient;
//import org.apache.http.impl.client.DefaultHttpClient;
//import org.apache.http.impl.client.HttpClientBuilder;
//import org.apache.http.impl.cookie.BasicClientCookie;
//import org.apache.http.message.BasicNameValuePair;
//import org.apache.http.message.BufferedHeader;
//import org.apache.http.util.EntityUtils;
//import org.springframework.stereotype.Component;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.util.ArrayList;
//import java.util.Date;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.CountDownLatch;
//
//import static com.sh.upload.constant.UploadConstant.BILI_VIDEO_CHUNK_UPLOAD_URL;
//
///**
// * @author caiWen
// * @date 2023/1/26 16:52
// */
//@Component
//@Slf4j
//@Data
//public class BiliFileUploadRunnable implements Runnable {
//    /**
//     * 视频上传地址
//     */
//    private String uploadUrl;
//
//    /**
//     * 视频需要上传到服务器的文件名字
//     */
//    private String serverFileName;
//
//    /**
//     * 视频总文件
//     */
//    private File file;
//
//    /**
//     * 文件id
//     */
//    private String uploadId;
//
//    /**
//     * 分块编号
//     */
//    private int chunkNo;
//
//    /**
//     * 分块总数
//     */
//    private int chunkTotalCount;
//
//    /**
//     * 当前分段大小
//     */
//    private long partSize;
//
//    /**
//     * 当前分段在输入流中的起始位置
//     */
//    private long partStart;
//
//    /**
//     * 分块上传时间
//     */
//    private long startTime;
//
//    /**
//     * cookies值
//     */
//    private String cookies;
//
//    private String xUposAuth;
//
//    /**
//     *
//     */
//    private CountDownLatch countDownLatch;
//
//
//    public BiliFileUploadRunnable(File videoFile, String cookies, String uploadUrl, String uploadId,
//            String serverFileName,
//            int chunkNo,
//            int chunkTotalCount, CountDownLatch countDownLatch, long partSize, long partStart, long startTime) {
//        this.file = videoFile;
//        this.cookies = cookies;
//        this.uploadUrl = uploadUrl;
//        this.serverFileName = serverFileName;
//        this.chunkNo = chunkNo;
//        this.chunkTotalCount = chunkTotalCount;
//        this.countDownLatch = countDownLatch;
//        this.partSize = partSize;
//        this.partStart = partStart;
//        this.startTime = startTime;
//        this.uploadId = uploadId;
//    }
//
////        @Override
////        public void run() {
////            try {
////                log.info("begin upload chunk, {}/{} video are uploading...", chunkNo, chunkTotalCount);
////                FileInputStream fis = new FileInputStream(file);
////                //跳过起始位置
////                fis.skip(partStart);
////                String videoUploadUrl = String.format(BILI_VIDEO_CHUNK_UPLOAD_URL, uploadUrl, chunkNo, uploadId,
////                        chunkTotalCount, chunkNo, partSize, partStart + partSize, file.length());
////
////                CloseableHttpClient ht = HttpClientBuilder.create().build();
////                HttpPut httpPut = new HttpPut(videoUploadUrl);
////
////
////
////                httpPut.setEntity(new InputStreamEntity(fis, partSize));
////                httpPut.setHeader("Accept","*/*");
////                httpPut.setHeader("Accept-Encoding","gzip, deflate, br");
////                httpPut.setHeader("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
////                httpPut.setHeader("Cookie", "PHPSESSID=" + serverFileName);
////                httpPut.setHeader("Origin","https://member.bilibili.com");
////                httpPut.setHeader("Referer","https://member.bilibili.com/video/upload.html");
//////                put.setHeader("X-Upos-Auth",upload.json.get("auth").toString());
////
////                HttpResponse response = ht.execute(httpPut);
////                String sss = EntityUtils.toString(response.getEntity(), "UTF-8");
////                System.out.println("-----" + JSON.toJSONString(sss) + "-----");
////            } catch (Exception e) {
////                log.error("error", e);
////                System.out.println("fuck");
////            }
////        }
//
//    @Override
//    public void run() {
//        try {
//            FileInputStream fis = new FileInputStream(file);
//            fis.skip(partStart);
//
//            Map<String, String> headers = BiliVideoUploadManager.BILI_HEADERS;
//            headers.put("Cookie", "PHPSESSID=" + serverFileName);
//
//            MD5 md5 = MD5.create();
//            String s = md5.digestHex(fis, Integer.valueOf(String.valueOf(partSize)));
//            System.out.println(s);
//
////            paramsObj.put("filesize", partSize);
////            paramsObj.put("chunk", chunkNo);
////            paramsObj.put("chunks", chunkTotalCount);
////            paramsObj.put("file", inputStreamEntity);
////            paramsObj.put("md5", s);
//
//            CookieStore cookieStore = new BasicCookieStore();
//            BasicClientCookie cookie = new BasicClientCookie("PHPSESSID", serverFileName);
//            cookie.setPath("/");
//            cookie.setSecure(true);
//            cookie.setExpiryDate(DateUtils.addDays(new Date(), 3));
//            cookieStore.addCookie(cookie);
//
//
//            CloseableHttpClient httpclient = HttpClientBuilder.create()
//                    .setDefaultCookieStore(cookieStore)
//                    .build();
//
//            HttpPost httppost = new HttpPost(uploadUrl);
//            httppost.addHeader("Content-type", "application/x-www-form-urlencoded");
//            httppost.addHeader("Connection", "alive");
//            httppost.addHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
//            httppost.addHeader("User-Agent",
//                    "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) "
//                            + "Chrome/109.0.0.0 Mobile Safari/537.36 Edg/109.0.1518.55");
//            httppost.addHeader("Connection", "keep-alive");
//            httppost.addHeader("Accept-Encoding", "gzip,deflate");
//            httppost.addHeader("Cookie", "PHPSESSID=" + serverFileName);
//
//            InputStreamEntity inputStreamEntity = new InputStreamEntity(fis, partSize);
//
//            inputStreamEntity.setContentType("application/octet-stream");
//
////            List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>();
////            nameValuePairs.add(new BasicNameValuePair("version", "2.0.0.1054"));
////            nameValuePairs.add(new BasicNameValuePair("chunk", String.valueOf(chunkNo)));
////            nameValuePairs.add(new BasicNameValuePair("chunks", String.valueOf(chunkTotalCount)));
////            nameValuePairs.add(new BasicNameValuePair("md5", s));
////            nameValuePairs.add(new BasicNameValuePair("filesize", String.valueOf(partSize)));
//            log.info("partsiz：{}, chunk:{}, chunks: {}, md5:{}",partSize, chunkNo, chunkTotalCount, s);
//
////            FormBodyPartBuilder.create()
////                    .setName("version")
////                    .setBody(new StringBody("2.0.0.1057", ContentType.TEXT_PLAIN))
////                    .build();
//
//
////            HttpEntity httpEntity = MultipartEntityBuilder.create()
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("version")
////                            .setBody(new StringBody("2.0.0.1054", ContentType.TEXT_PLAIN))
////                            .build())
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("filesize")
////                            .setBody(new StringBody(String.valueOf(partSize), ContentType.TEXT_PLAIN))
////                            .build())
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("chunk")
////                            .setBody(new StringBody(String.valueOf(chunkNo), ContentType.TEXT_PLAIN))
////                            .build())
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("chunks")
////                            .setBody(new StringBody(String.valueOf(chunkTotalCount), ContentType.TEXT_PLAIN))
////                            .build())
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("md5")
////                            .setBody(new StringBody(s, ContentType.TEXT_PLAIN))
////                            .build())
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("file")
////                            .setBody(new InputStreamBody(fis, ContentType.APPLICATION_OCTET_STREAM))
////                            .build())
////                    .build();
////
////            BufferedHttpEntity bufferedHttpEntity = new BufferedHttpEntity(httpEntity);
//
//
//            HttpEntity httpEntity = MultipartEntityBuilder.create()
//                    .setMode(HttpMultipartMode.STRICT)
//                    .setCharset(Consts.UTF_8)
//                    .setContentType(ContentType.MULTIPART_FORM_DATA)
//                    .addPart("version", new StringBody("2.0.0.1057", ContentType.TEXT_PLAIN))
//                    .addPart("filesize", new StringBody(String.valueOf(partSize), ContentType.TEXT_PLAIN))
//                    .addPart("chunk", new StringBody(String.valueOf(chunkNo), ContentType.TEXT_PLAIN))
//                    .addPart("chunks", new StringBody(String.valueOf(chunkTotalCount), ContentType.TEXT_PLAIN))
//                    .addPart("md5", new StringBody(s, ContentType.TEXT_PLAIN))
////                    .addPart(FormBodyPartBuilder.create()
////                            .setName("file")
////                            .setBody(new FileBody(file))
////                            .build())
//
//                    .addBinaryBody("file", inputStreamEntity.getContent(), ContentType.APPLICATION_OCTET_STREAM,
//                            "videoName")
////                    .addPart("name", new StringBody("videoName", ContentType.TEXT_PLAIN))
////                    .addPart("file", new InputStreamBody(fis, ContentType.APPLICATION_OCTET_STREAM))
//                    .build();
//
////            httppost.setEntity(bufferedHttpEntity);
//
//
//            CloseableHttpResponse res = httpclient.execute(httppost);
//            System.out.println("--------------------------------" + EntityUtils.toString(res.getEntity(), "UTF-8") + "------");
//
//
//
//        } catch (Exception e) {
//            log.error(e.getMessage());
//            e.printStackTrace();
//        }
//    }
//
//}
