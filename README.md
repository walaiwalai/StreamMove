## 1. 介绍

该项目用java、python（借助streamlink）实现直播/录像下载、视频剪辑、视频上传。
**支持录播平台**：

- Huya
- Afreecatv
- Bilibili
- Twitch
- Chzzk
- 抖音

**支持视频上传平台**：

- Bilibili
- 阿里云盘
- 抖音

## 2. 启动

采用docker部署，目前在Ubantu上测试过，其他平台没有测试过。

### 1. 创建项目路径
```shell
mkdir -p /home/admin/stream/{download,logs,dump,account,thumbnail}

```

### 2. 创建项目初始化配置文件 & 直播用户配置
```shell
cd /home/admin
git clone https://github.com/walaiwalai/StreamMove.git
cd StreamMove


# 填写init.json和streamer.json配置信息（见下）
cd /home/admin/StreamMove/sh-config/src/main/resources/config
vim init.json
vim streamer.json
```

### 3. 编译
```shell
# 没有maven安装以下：apt intall maven
mvn package

# 拷贝到项目目录
cp /home/admin/StreamMove/sh-config/target/classes/config/init.json /home/admin/stream
cp /home/admin/StreamMove/sh-config/target/classes/config/streamer.json /home/admin/stream
cp /home/admin/StreamMove/sh-start/target/sh-start-1.0-SNAPSHOT.jar /home/admin/stream

```
### 4. docker部署
```shell
# 1. 创建镜像
docker build -t stream-ocr:latest -f Dockerfile-ocr .
docker build -t stream-base:latest -f Dockerfile-base .
docker build -t stream-move:latest -f Dockerfile .

# 2.启动docker
docker-compose up -d
```

## 3. 录播配置文件配置

配置文件分为两个，分别为init.json和streamer.json。将这两个文件放在/home/admin/stream/目录下

### 3.1 init.json文件

```json
{
  "roomCheckCron": "0 0/5 * * * ?",
  "fileCleanCron": "0 3 0 * * ?",
  "configRefreshCron": "0 0/30 * * * ?",
  "videoSavePath": "/home/admin/stream/download",
  "accountSavePath": "/home/admin/stream/account",
  "maxRecordingCount": 2,
  "videoPartLimitSize": 10,
  "accessToken": "xxx",
  "mid": 123456789,
  "refreshToken": "xxx",
  "targetFileId": "xxx",
  "weComWebhookSecret": "xxx",
  "weComAgentId": "xxx",
  "weComSecret": "xxx",
  "weComEventToken": "xxx",
  "weComEncodingAesKey": "xxx",
  "weComCorpId": "xxx"
}

```

- **roomCheckCron**     必填，主播是否在线的检测corn表达式，最好不要太频繁
- **fileCleanCron**     必填，针对上传完的视频进行定时清理的cron表达式
- **configRefreshCron** 必填，针对init.json和streamer.json中的配置重新刷新的cron表达式
- **videoSavePath**     必填，视频下载目录
- **accountSavePath**   必填，采用模拟登录cookies信息保存的文件
- **videoPartLimitSize** 非必填，上传B站的单个视频的最小大小（M）,默认是0
- **maxRecordingCount**  非必填，同时最大的录播主播个数，默认2

B站相关

- **biliCookies**      B站网页端的cookies值（platform=BILI_WEB需要）
- **accessToken**      B站客户端上传的accessToken(platform=BILI_CLIENT需要)
- **mid**              B站上传的身份Id(platform=BILI_CLIENT需要)

阿里云盘相关

- **refreshToken**     阿里云盘的refreshToken（platform=ALI_DRIVER需要）
- **targetFileId**     阿里云盘的上传的目标文件夹fileId（platform=ALI_DRIVER需要）

企业微信相关（用于通信）
- **weComWebhookSecret**  非必填，企业微信推送通知的webhook的secret，用于通知消息
- **weComAgentId**  非必填，企业微信微应用id，用于扫码二维码发送消息
- **weComSecret**  非必填，企业微信微应用secret，用于扫码二维码发送消息
- **weComEventToken**  非必填，企业微信微应用消息回调token，用于手机验证码验证
- **weComEncodingAesKey**  同上
- **weComCorpId**  非必填，企业微信组织id

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
    "lastVodCnt": 1,
    "videoPlugins": [
      "LOL_HL_VOD_CUT"
    ],
    "uploadPlatforms": [
      "BILI_CLIENT"
    ],
    "location": "杭州",
    "coverFilePath": "/home/admin/stream/thumbnail/Chovy.jpg"
  }
]
```

- name 主播名称
- roomUrl 直播地址
- templateTitle 投稿视频名称

**B站投稿相关**
- desc 投稿视频描述
- source 投稿视频来源描述
- dynamic 投稿稿件动态
- tid 稿件分区
- tags 稿件标签，至少一个，总数量不能超过12个，并且单个不能超过20个字
- cover 投稿封面地址，一般b站上传图片能拿到
- 
**抖音投稿相关**
- location 定位信息
- preViewFilePath 预览封面文件地址

**其他**
- recordWhenOnline true为实时录播，false为上传视频后下载
- lastRecordTime 上次录播时间
- videoPlugins 录播视频插件列表，目前有：
    - BATCH_SEG_MERGE recordWhenOnline为true才有效，合并视频切片
    - LOL_HL_VOD_CUT recordWhenOnline为true才有效，英雄联盟精彩视频片段选取，需要用到上面stream-ocr
- uploadPlatforms 上传的平台列表，目前有：
    - BILI_CLIENT B站客户端方式（可多P上传）
    - ALI_DRIVER 阿里云盘
    - DOU_YIN 抖音（只有精彩片段）

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

https://github.com/KLordy/auto_publish_videos

https://github.com/dreammis/social-auto-upload