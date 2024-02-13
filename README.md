
## 介绍
该项目用java实现了一个直播录制工具，实时录制直播保存为视频文件，向B站自动投稿。代码借鉴了StreamerHelper、DouyinLiveRecorder
等。目前支持平台有：
- 虎牙
- 斗鱼
- Afreecatv
- Bilibili

## 容器部署
- 配置sh-config中的init.json和streamer.json
- 执行Dockerfile文件：
```shell
docker build -f Dockerfile -t smove:v1 .
```

- 启动容器
```shell
docker run --name smove \
-v /home/admin/stream/init.json:/home/admin/stream/init.json \
-v /home/admin/stream/streamer.json:/home/admin/stream/streamer.json \
--restart always smove:v1
```

## 配置说明
init.json文件
- roomCheckCron 必填，主播是否在线的检测corn表达式，最好不要太频繁
- fileCleanCron 必填，针对上传完的视频进行定时清理的cron表达式
- configRefreshCron 必填，针对init.json和streamer.json中的配置重新刷新的cron表达式
- videoSavePath 必填，录播的保存地址
- segmentDuration 非必填，录播单个视频长度（秒）
- quality 非必填，录播质量，原画/超清/高清/标清
- videoPartLimitSize 非必填，上传B站的单个视频的最小大小（M）

- afreecaTvCookies 非必填，录播afreecaTv最好带上这个cookies

- uploadType 必填，B站上传方式，1为网页端上传/2为客户端
- mid B站上传的身份Id（uploadType是2的时候才需要，具体怎么获得下面有说明，具体怎么获得下面有说明）
- accessToken B站客户端上传的accessToken（uploadType是2的时候才需要，具体怎么获得下面有说明）
- biliCookies B站网页端的cookies值（uploadType是1的时候才需要）

- weComSecret 非必填，企业微信推送通知的webhook的secret，用于通知消息

streamer.json

- name 主播名称
- roomUrl 直播地址
- templateTitle 投稿视频名称
- desc 投稿视频描述
- source 投稿视频来源描述
- dynamic 投稿稿件动态
- tid 稿件分区
- tags 稿件标签，至少一个，总数量不能超过12个，并且单个不能超过20个字
- cover 投稿封面地址，一般b站上传图片能拿到
- recordWhenOnline true为实时录播，false为上传视频后下载（目前仅Afreecatv支持）
- lastRecordTime 上次录播时间，仅recordWhenOnline为false生效

以上配置借鉴了[StreamerHelper](https://github.com/ZhangMingZhao1/StreamerHelper)

例子：
init.json

```json
{
  "roomCheckCron": "0/15 * * * * ?",
  "fileCleanCron": "0 3 0 * * ?",
  "configRefreshCron": "0 0/30 * * * ?",
  "videoSavePath": "C:\\Users\\caiwen\\Desktop\\download",
  "segmentDuration": 3600,
  "quality": "超清",
  "videoPartLimitSize": 10,
  "biliCookies": "buvid3=xxx",
  "accessToken": "xxx",
  "mid": 123456789,
  "uploadType": 2,
  "weComSecret": "xxx",
  "afreecaTvCookies": "_au=xxx"
}

```

stream.json

```json
[
  {
    "name": "Chovy",
    "roomUrl": "https://play.afreecatv.com/wlgnsdl0303",
    "templateTitle": "【${name}直播回放】 ${time}",
    "desc": "Chovy直播录播",
    "source": "Chovy直播间：https://bj.afreecatv.com/wlgnsdl0303",
    "dynamic": "",
    "tid": 171,
    "tags": [
      "Chovy",
      "英雄联盟",
      "直播录播",
      "直播回放",
      "GEN",
      "Chovy直播直播",
      "中单",
      "第一视角"
    ],
    "cover": "xxx",
    "recordWhenOnline": false,
    "lastRecordTime": "2024-12-24 12:00:00"
  }
]
```
安装ffmpeg
安装python，并且安装torch

## 关于视频上传和录制

- 视频录制去掉了ffmpeg的依赖，需要自行安装ffmpeg并支持libx264和libfdk_aac编码。
- B站Web端视频仅支持单视频上传，客户端支持多P上传
- 支持“哔哩哔哩投稿工具”客户端（版本2.3.0.1089）分P上传视频，其中init.json中的accessToken和mid抓包“member.bilibili.com/preupload”这个请求。

## 参考项目：
https://github.com/ZhangMingZhao1/StreamerHelper
https://github.com/ihmily/DouyinLiveRecorder
https://github.com/AsaChiri/DDRecorder