## 1. 介绍

该项目用java实现了一个直播录制工具，实时录制直播/下载直播录像到本地，进行自动剪辑，并向指定平台进行视频上传。
**支持录播平台**：

- 虎牙
- 斗鱼
- Afreecatv
- Bilibili

**支持上传平台**：

- Bilibili
- 阿里云盘

## 2. 启动

这边以centos为例

1. 安装ffmpeg（必须）

```shell
yum install ffmpeg
```

安装完成后运行`ffmpeg -version`看是否有输出，有输出说明安装成功。

2. 安装飞浆paddle进行作为ocr识别服务（非必须）

```shell
# 拉取进行paddle镜像
docker pull paddlepaddle/paddle:2.6.0

# 运行容器
docker run -d --network host paddlepaddle/paddle:2.6.0

# 进入容器执行，安装ch_pp-ocrv3并启动服务
pip install paddlepaddle -i https://mirror.baidu.com/pypi/simple
pip install paddlehub -i https://mirror.baidu.com/pypi/simple

hub install ch_pp-ocrv3==1.2.0
hub serving start -m ch_pp-ocrv3
```

3. 启动录播程序
   打包获取sh-start-1.0-SNAPSHOT.jar，放到/home/admin/stream，执行：

```shell
java -Dfile.encoding=utf-8 -Duser.timezone=GMT+08 -Dspring.profiles.active=prod -Xms512m -Xmx1536m -jar /home/admin/stream/sh-start-1.0-SNAPSHOT.jar &
```

## 3. 录播配置文件配置

配置文件分为两个，分别为init.json和streamer.json。将这两个文件放到机子的/home/admin/stream/目录下

### 3.1 init.json文件

```json
{
  "roomCheckCron": "0 0/5 * * * ?",
  "fileCleanCron": "0 3 0 * * ?",
  "configRefreshCron": "0 0/30 * * * ?",
  "videoSavePath": "/home/admin/stream/download",
  "segmentDuration": 3600,
  "quality": "超清",
  "videoPartLimitSize": 10,
  "weComSecret": "xxx",
  "biliCookies": "buvid3=xxx",
  "accessToken": "xxx",
  "mid": 123456789,
  "refreshToken": "xxx",
  "targetFileId": "xxx",
  "afreecaTvCookies": "xxx"
}

```

- **roomCheckCron**     必填，主播是否在线的检测corn表达式，最好不要太频繁
- **fileCleanCron**     必填，针对上传完的视频进行定时清理的cron表达式
- **configRefreshCron** 必填，针对init.json和streamer.json中的配置重新刷新的cron表达式
- **videoSavePath**     必填，视频下载目录
- **segmentDuration**   非必填，单个录播单个视频长度（秒）
- **quality**           非必填，录播质量，原画/超清/高清/标清, 默认原画
- **videoPartLimitSize** 非必填，上传B站的单个视频的最小大小（M）,默认是0
- **weComSecret**       非必填，企业微信推送通知的webhook的secret，用于通知消息

B站相关

- **biliCookies**      B站网页端的cookies值（platform=BILI_WEB需要）
- **accessToken**      B站客户端上传的accessToken(platform=BILI_CLIENT需要)
- **mid**              B站上传的身份Id(platform=BILI_CLIENT需要)

阿里云盘相关

- **refreshToken**     阿里云盘的refreshToken（platform=ALI_DRIVER需要）
- **targetFileId**     阿里云盘的上传的目标文件夹fileId（platform=ALI_DRIVER需要）

afreecaTv相关

- **afreecaTvCookies**  非必填，录播afreecaTv平台最好带上这个cookies

### 3.2 streamer.json文件

```json
[
  {
    "name": "Chovy",
    "roomUrl": "https://play.afreecatv.com/wlgnsdl0303",
    "templateTitle": "【${name}直播回放】 ${time}",
    "desc": "Chovy直播录播",
    "source": "Chovy直播间：xxx",
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
    "lastRecordTime": "2024-12-24 12:00:00",
    "videoPlugins": [
      "LOL_HL_VOD_CUT"
    ],
    "uploadPlatforms": [
      "BILI_CLIENT"
    ]
  }
]
```

- name 主播名称
- roomUrl 直播地址
  B站投稿相关
- templateTitle 投稿视频名称
- desc 投稿视频描述
- source 投稿视频来源描述
- dynamic 投稿稿件动态
- tid 稿件分区
- tags 稿件标签，至少一个，总数量不能超过12个，并且单个不能超过20个字
- cover 投稿封面地址，一般b站上传图片能拿到

其他

- recordWhenOnline true为实时录播，false为上传视频后下载（目前仅Afreecatv支持）
- lastRecordTime 上次录播时间，仅recordWhenOnline为false生效
- videoPlugins 录播视频插件列表，目前有：
    - BATCH_SEG_MERGE recordWhenOnline为true才有效，合并视频切片
    - LOL_HL_VOD_CUT recordWhenOnline为true才有效，英雄联盟精彩视频片段选取，需要用到上文的paddle
- uploadPlatforms 上传的平台列表，目前有：
    - BILI_CLIENT B站客户端方式（可多P上传）
    - BILI_WEB B站网页端方式（只能上传1个视频）
    - ALI_DRIVER 阿里云盘删除更换

## 4. 一些参数的抓取

**accessToken和mid**

“哔哩哔哩投稿工具”客户端（版本2.3.0.1089）上传视频，抓包“member.bilibili.com/preupload”这个请求。

**refreshToken**

登录阿里云盘，打开开发者工具，DevTools -> Application -> Local Storage -> token中的refreshToken

**targetFileId**

随便打开阿里云盘点击到目标文件夹，链接最后就是targetFileId
如：https://www.aliyundrive.com/drive/file/backup/{targetFileId}

## 参考项目：
https://github.com/ZhangMingZhao1/StreamerHelper

https://github.com/ihmily/DouyinLiveRecorder

https://github.com/AsaChiri/DDRecorder