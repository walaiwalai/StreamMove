//package com.sh.upload.manager;
//
//import com.alibaba.fastjson.JSON;
//import com.alibaba.fastjson.JSONObject;
//import lombok.Getter;
//import lombok.Setter;
//import lombok.extern.slf4j.Slf4j;
//import net.dongliu.requests.Requests;
//import org.apache.commons.lang.StringUtils;
//import org.apache.http.HttpResponse;
//import org.apache.http.client.methods.HttpPut;
//import org.apache.http.entity.InputStreamEntity;
//import org.apache.http.impl.client.CloseableHttpClient;
//import org.apache.http.impl.client.HttpClientBuilder;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//
///**
// * Created by Medivh on 2020/2/24.
// * 愿我出走半生,归来仍是少年
// */
//@Setter
//@Getter
//@Slf4j
//public class BiliUploadModelV2 {
//    private String name;
//    private long size;
//    private String cookies;
//    private Map<String,Object> json;
//    private String url;
//    private String uploadId;
//    private boolean flag;
//    //（线程等待时间与线程CPU时间之比 + 1）* CPU数目 cpu时间大概在0。5秒 等待时间比较长 大概在1。2秒
//    private static  int poolSize  = Runtime.getRuntime().availableProcessors()*2+1;
//    HashMap  postJson ;
//
//
//    public BiliUploadModelV2(String name, long size , String cookies){
//        this.name = name;
//        this.size = size;
//        this.cookies = cookies;
//        String url = "https://member.bilibili.com/preupload?name="+name+"&size="+size+"&r=upos&profile=ugcupos%2Fbup&ssl=0&version=2.7.1&build=2070100&os=upos&upcdn=ws";
//        this.json = JSONObject.parseObject(get(url),Map.class);
//
//        if("1".equals(json.get("OK")+"")){
//            this.url = "https:"+this.json.get("endpoint")+json.get("upos_uri").toString().split("upos:/")[1];
//            getUploadid();
//            this.flag = true;
//        }else{
//            this.flag = false;
//        }
//
//    }
//    public String get(String url){
//
//        Map<String,String> headers = new HashMap();
//        headers.put("Accept","*/*");
//        headers.put("Accept-Encoding","gzip, deflate, br");
//        headers.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
//        headers.put("Cookie",this.cookies);
//        String response =  Requests.get(url).headers(headers).send().readToText();
//        return  response;
//
//    }
//
//    public void getUploadid(){
//        Map<String,Object> map = JSONObject.parseObject(post(this.url + "?uploads&output=json",null));
//        this.uploadId = map.get("upload_id").toString();
//
//
//    }
//    public String postJSon(String url,String json){
//
//        Map<String,String> headers = new HashMap();
//        headers.put("Accept","*/*");
//        headers.put("Accept-Encoding","gzip, deflate, br");
//        headers.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
//        headers.put("Cookie",this.cookies);
//        headers.put("Origin","https://member.bilibili.com");
//        headers.put("Referer","https://member.bilibili.com/video/upload.html");
//        headers.put("X-Upos-Auth",this.json.get("auth").toString());
//
//
//        return Requests.post(url).headers(headers).body(json).send().readToText();
//
//
//
//
//
//    }
//    public String post(String url,HashMap map){
//
//        Map<String,String> headers = new HashMap();
//        headers.put("Accept","*/*");
//        headers.put("Accept-Encoding","gzip, deflate, br");
//        headers.put("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
//        headers.put("Cookie",this.cookies);
//        headers.put("Origin","https://member.bilibili.com");
//        headers.put("Referer","https://member.bilibili.com/video/upload.html");
//        headers.put("X-Upos-Auth",this.json.get("auth").toString());
//        String response = "";
//        if(map == null) {
//            response = Requests.post(url).headers(headers).send().readToText();
//        }else{
//            response = Requests.post(url).headers(headers).body(map).send().readToText();
//        }
//        return response;
//
//    }
//
//
//
//
//
//    public void end(HashMap map){
//        String src = "?output="+"json"+"&name="+name+"&profile=ugcupos%%2Fbup&uploadId="+this.uploadId+"&biz_id="+this.json.get("biz_id");
//        log.info(post(this.url + src, map));
//        url = "https://member.bilibili.com/x/vu/web/add?csrf=" + csrf();
//        log.info(postJSon(url, JSON.toJSONString(postJson)));
//
//    }
//    public String csrf(){
//        return StringUtils.substringBetween(this.cookies,"bili_jct=",";");
//    }
//
//
//    public void partFileUpload(String url,File file) throws IOException, InterruptedException {
//
//        FileInputStream fis = new FileInputStream(file);
//        long partSize =Long.parseLong(json.get("chunk_size").toString());
//        long fileSize = fis.getChannel().size();
//        int  partCount = (int) (fileSize/partSize);
//        if(partSize*partCount<fileSize){
//            partCount++;
//        }
//        log.info("文件大小{}MB",fileSize/1024/1024);
//        log.info("分配线程池{}",poolSize);
//        log.info("文件一共分为{}",partCount+"块,进度0/"+partCount);
//
//        ExecutorService service = Executors.newFixedThreadPool(poolSize);
//
//        long startTime = System.currentTimeMillis();
//        CountDownLatch countDownLatch = new CountDownLatch(partCount);
//        //这里需要优化 线程池自定义
//        for (int i = 0; i <partCount ; i++) { //partCount
//            int num = i+1;
//            //当前分段起始位置
//            long partStart=i*partSize;
//            //当前分段大小  如果为最后一个大小为fileSize-partStart  其他为partSize
//            long curPartSize=(i+1==partCount)?(fileSize-partStart):partSize;
//
//            FileUploadRunnable fileUploadRunnable = new   FileUploadRunnable(this,url,num,countDownLatch,file,curPartSize,partStart,partCount,startTime);
//            service.submit(fileUploadRunnable);
//
//        }
//
//
//
//        countDownLatch.await(1, TimeUnit.HOURS);
//        service.shutdown();
//        log.info("分块文件全部上传完毕");
//        ArrayList list = new ArrayList();
//        for(int i =0;i<partCount;i++){
//            HashMap map = new HashMap();
//            map.put("partNumber",partCount);
//            map.put("eTag","'etag'");
//            list.add(map);
//        }
//        HashMap map = new HashMap();
//        map.put("parts",list);
//
//        end(map);
//
//    }
//
//
//    public void set_post_json(HashMap  map){
//        String  filename = this.json.get("upos_uri").toString().split("upos://ugc/")[1].split(".mp4")[0];
//        ArrayList arrayList = JSONObject.parseObject( map.get("videos").toString(),ArrayList.class);
//        Map<String,Object> maps = JSONObject.parseObject(arrayList.get(0).toString(),Map.class);
//
//        maps.put("filename",filename);
//        arrayList.set(0,maps);
//        map.put("videos",arrayList);
//        this.postJson = map;
//
//
//    }
//    public class FileUploadRunnable implements Runnable {
//
//        private String url;
//
//        //文件id
//        private String fileId;
//
//        //分块编号
//        private int num;
//
//        private CountDownLatch countDownLatch;
//
//        //当前分段大小
//        private long partSize;
//
//        //当前分段在输入流中的起始位置
//        private long partStart;
//
//        //总文件
//        private File file;
//
//        private BiliUploadModelV2 upload;
//        private int partCount;
//        private long startTime;
//        public FileUploadRunnable(BiliUploadModelV2 upload, String url, int num, CountDownLatch countDownLatch, File file, long partSize, long partStart, int partCount,long startTime) {
//            this.url = url;
//            this.upload = upload;
//            this.num = num;
//            this.countDownLatch = countDownLatch;
//            this.partSize = partSize;
//            this.partStart = partStart;
//            this.file = file;
//            this.partCount = partCount;
//            this.startTime = startTime;
//
//        }
//
//        public void run() {
//            try {
//                FileInputStream fis = new FileInputStream(file);
//                CloseableHttpClient ht = HttpClientBuilder.create().build();
//
//                HttpPut put = new HttpPut(
//                        url + "?partNumber=" + num + "&uploadId=" + upload.uploadId + "&chunks=" + partCount + "&chunk="
//                                + num + "&size=" + partSize + "&start=" + partStart + "&end=" + (partStart + partSize)
//                                + "&size=" + partSize + "&total=" + file.length());
//
//                HttpResponse response;
//                //跳过起始位置
//                fis.skip(partStart);
//                // log.info("开始上传分块:" + num);
//                //请求接收分段上传的地址
//                put.setEntity(new InputStreamEntity(fis, partSize));
//                //Map<String,String> headers = new HashMap();
//                put.setHeader("Accept","*/*");
//                put.setHeader("Accept-Encoding","gzip, deflate, br");
//                put.setHeader("User-Agent","Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36");
//                put.setHeader("Cookie",upload.cookies);
//                put.setHeader("Origin","https://member.bilibili.com");
//                put.setHeader("Referer","https://member.bilibili.com/video/upload.html");
//                put.setHeader("X-Upos-Auth",upload.json.get("auth").toString());
//
//                response = ht.execute(put);
//                if (response.getStatusLine().getStatusCode() == 200) {
//                    String ret = EntityUtils.toString(response.getEntity(), "utf-8");
//
//                    countDownLatch.countDown();
//                    log.info("进度{},耗时:{}秒",(partCount-countDownLatch.getCount())+"/"+partCount,(System.currentTimeMillis()-startTime)/1000);
//
//                }else{
//                    log.error("分块"+num+"上传失败");
//                    countDownLatch.countDown();
//                }
//
//            } catch (Exception e) {
//                log.error(e.getMessage());
//                e.printStackTrace();
//            }
//        }
//    }
//    //    def set_post_json(self, json):
//    //    filename = self.json['upos_uri'].split('upos://ugc/')[1].split('.mp4')[0]
//    //    json['videos'][0]['filename'] = filename
//    //    self.post_json = json
//
//
//    //                "Accept-Encoding": "gzip, deflate, br",
//    //                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) Chrome/63.0.3239.132 Safari/537.36",
//    //                "Cookie": self.cookies,
//    //    }
//    //
//    //    r = requests.get(url=url, headers=headers, params=data, timeout=10, verify=False)
//    //            return r.content.decode()
//    public  static  void  main(String args[]) throws IOException, InterruptedException {
//
//
//        String  bilibilicookies = "LIVE_BUVID=AUTO4715785517982541; DedeUserID=25437908;  SESSDATA=228af7e9%2C1585093835%2C48daa321; bili_jct=4d91f1d39d2a066feaf0c274622ad44c;";
//
//
//        //https://upos-sz-upcdnws.bilivideo.com/ugc/m200225ws2bm6rg5fj0p0g2thhwm7ytd.mp4?partNumber=5&uploadId=21a267b2c128c566f7d234275efb483b&chunks=5&size=8388608&start=33554432&end=41943040&size=8388608&total=840832296开始上传分块:5
//        //https://upos-sz-upcdnws.bilivideo.com/ugc/m200225ws2bm6rg5fj0p0g2thhwm7ytd.mp4?partNumber=39&uploadId=21a267b2c128c566f7d234275efb483b&chunks=39&size=8388608&start=318767104&end=327155712&size=8388608&total=840832296开始上传分块:39
//        File file = new File("/Users/chensk/Downloads/42.mp4");
//        BiliUploadModelV2 upload = new BiliUploadModelV2("1232333.mp4",file.length(),bilibilicookies);
//        //正确 https://upos-sz-upcdnws.bilivideo.com/ugc/m200225ws2r7r332w8kwvg1whjtzlhgt.mp4?partNumber=1&uploadId=31aa73b561fa14e1f497fd765b74c7b7&chunk=3&chunks=191&size=8388608&start=0&end=8388608&total=840832296
//        //错误 https://upos-sz-upcdnws.bilivideo.com/ugc/m200225ws13gru80a8sjyg2q9sudj6n0.mp4?partNumber=1&uploadId=279cbb77fceed3d51095ba242e250722&chunks=1&size=8388608&start=0&end=8388608&size=8388608&total=840832296
//        HashMap json = JSONObject.parseObject("{\n" +
//                "\t\"copyright\": 1,\n" +
//                "\t\"videos\": [{\n" +
//                "\t\t\"filename\": 0,\n" +
//                "\t\t\"title\": \"42\",\n" +
//                "\t\t\"desc\": \"\"\n" +
//                "\t}],\n" +
//                "\t\"source\": \"42\",\n" +
//                "\t\"tid\": 21,\n" +
//                "\t\"cover\": \"\",\n" +
//                "\t\"title\": \"42\",\n" +
//                "\t\"tag\": \"42\",\n" +
//                "\t\"desc_format_id\": 0,\n" +
//                "\t\"desc\": \"42\",\n" +
//                "\t\"dynamic\": \"#日漫#\",\n" +
//                "\t\"subtitle\": {\n" +
//                "\t\t\"open\": 0,\n" +
//                "\t\t\"lan\": \"\"\n" +
//                "\t}\n" +
//                "}",HashMap.class);
//
//        upload.set_post_json(json);
//        upload.partFileUpload(upload.url,file);
//
//
//
//    }
//}
