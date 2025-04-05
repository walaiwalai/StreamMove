## 1. 介绍

本项目用借助streamlink & alist & ffmpeg & playwright 等工具，用java实现直播/录像下载(streamlink)、视频剪辑(ffmpeg)
、视频上传(alist)的一体化流程。

**支持录播平台**：streamlink支持的所有平台，目前包括：

- Huya
- Afreecatv
- Bilibili
- Twitch
- Chzzk
- PandaLive
- 抖音
- 小红书

**支持视频上传平台**：

- 各大网盘，采用alist进行本地存储 -> 目标网盘（百度云盘、阿里云盘、夸克网盘等）
- Bilibili
- 抖音

## 2. 启动

采用docker部署，目前在Ubuntu上测试过，其他平台没有测试过。
容器分为三个：

- stream-move：整体的调度框架，包括下载、合并、视频上传
- stream-ocr：视频剪辑使用，提供一些ocr + 专门的图像识别功能
- alist：提供各种的网盘的传输服务

### 1. 创建路径

```shell
mkdir -p /home/admin/stream/{download,logs,dump,account,thumbnail}
```

### 2. 创建项目参数 & 初始化配置文件

```shell
git clone https://github.com/walaiwalai/StreamMove.git
cd StreamMove

# 复制一些基本配置文件
cp init/config.properties /home/admin/stream/config/config.properties
cp init/init.json /home/admin/stream/config/init.json
```

填写config.properties：

```properties
# redis配置（根据自身的redis配置调整）
spring.redis.host=localhost
spring.redis.port=6379
spring.redis.password=123456
spring.redis.database=0
# mysql配置（根据自身的mysql配置调整）
spring.datasource.url=jdbc:mysql://localhost/stream_move?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=123456
# alist服务配置（默认即可）
alist.server.host=alist
alist.server.username=admin
alist.server.password=123456
alist.server.port=5244
# ocr服务配置（默认即可）
ocr.server.host=stream-ocr
ocr.server.port=5000
# 企业微信的webhook的secret，用于各种消息通知
wecom.webhook.secret=
# 环境标识，默认即可
system.env.flag=default
```

填写init.json配置：

```json
{
  "roomCheckCron": "0/15 * * * * ?",
  "fileCleanCron": "0 3 0 * * ?",
  "configRefreshCron": "0 0/30 * * * ?",
  "videoSavePath": "/home/admin/stream/download",
  "accountSavePath": "/home/admin/stream/account",
  "videoPartLimitSize": 10,
  "biliCookies": "${哔哩哔哩网页端的cookies}",
  "accessToken": "${哔哩哔哩投稿工具客户端的accessToken}",
  "mid": "${哔哩哔哩投稿工具客户端的mid}",
  "twitchAuthorization": "${twitch的authorization}",
  "soopliveCookies": "${sooplive网站的cookies}",
  "soopUserName": "${sooplive网站的用户名}",
  "soopPassword": "${sooplive网站的密码}"
}
```

基础配置：

- **roomCheckCron**     必填，主播是否在线的检测corn表达式，最好不要太频繁
- **fileCleanCron**     必填，针对上传完的视频进行定时清理的cron表达式
- **configRefreshCron** 必填，刷新从数据库刷新主播配置的cron表达式
- **videoSavePath**     必填，视频下载目录
- **accountSavePath**   必填，采用playwright模拟登录cookies信息保存的文件
- **videoPartLimitSize** 非必填，上传单个视频的最小大小（M）,默认是0
- **maxRecordingCount**  非必填，同时最大的录播主播个数，默认2

录制twitch相关：

- **twitchAuthorization**：twitch的authorization（录制twitch直播最好有）

录制小红书相关：

- **xhsCookies**：小红书的cookies（录制小红书直播最好有）

录制youtube相关：

- **youtubeCookies**：youtube的cookies（录制youtube直播最好有）

录制Soop（原来的AfreecaTV）相关：

- **soopCookies**：Soop的cookies（录制Soop直播最好有）
- **soopUserName**：Soop的账号（录制Soop直播最好有，某些特殊直播必须有）
- **soopPassword**：Soop的密码（录制Soop直播最好有，某些特殊直播必须有）

上传B站（网页端）相关：

- **biliCookies**      B站网页端的cookies值

上传B站（客户端端）相关：

- **accessToken**      B站客户端上传的accessToken
- **mid**              B站上传的身份Id

### 3. 数据库配置

执行init/init-sql.sql创建录播信息表， 字段解释

- template_title：视频标题模板，支持名称和时间占位符，如：【${name}直播回放】 ${time}
- last_vod_cnt：需要录播的历史录像个数，在支持vod的下载的直播生效
- upload_platforms：上传的平台名称（多平台","分割）
    - BILI_CLIENT：B站客户端上传
    - BILI_WEB：B站网页端上传
    - DOU_YIN: 抖音上传
    - ALI_PAN: 阿里云盘上传
    - BAIDU_PAN: 百度云盘上传
    - QUARK_PAN: 夸克云盘上传
    - UC_PAN: UC云盘上传
- process_plugins：视频处理插件
    - LOL_HL_VOD_CUT: 英雄联盟直播精彩自动剪辑（效果还行吧...）
- tags：视频标签，支持多个，用逗号隔开
- max_merge_size：有些网盘上传限制单个文件大小，所以这边限制单个文件的最大大小
- env：环境标识，默认default

### 4. alist配置

在配置挂在目录时，对应的**挂载路径**名设置为：

- 百度云盘：/百度网盘
- 阿里云盘：/阿里云盘
- 夸克云盘：/夸克云盘
- UC网盘：/UC网盘

### 4. 本地编译

```shell
# 没有maven安装以下：apt install maven
mvn package

# 创建镜像（比较耗时）
docker build -t stream-base:latest -f Dockerfile-base .
docker build -t stream-ocr:latest -f Dockerfile-ocr .
docker build -t stream-move:latest -f Dockerfile .

# 启动docker
docker-compose up -d
```

## 5. 一些参数的抓取

**accessToken和mid**

“哔哩哔哩投稿工具”客户端（版本2.3.0.1089）上传视频，抓包“member.bilibili.com/preupload”这个请求。

## 参考项目：

https://github.com/ZhangMingZhao1/StreamerHelper

https://github.com/ihmily/DouyinLiveRecorder

https://github.com/AsaChiri/DDRecorder

https://github.com/KLordy/auto_publish_videos

https://github.com/dreammis/social-auto-upload