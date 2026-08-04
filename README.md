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
- 淘宝直播

**支持视频上传平台**：

- 各大网盘，采用alist + rclone将本地存储 -> 目标网盘（百度云盘、阿里云盘、夸克网盘等）
- Bilibili
- 抖音（页面自动化与 Java HTTP 两种方式）

**后续计划**：

- AI自动标题、封面生成
- AI自动精彩剪辑

## 2. 启动

采用docker部署，目前在Ubuntu上测试过，其他平台没有测试过。
容器分为三个：

- stream-move：整体的调度框架，包括下载、合并、视频上传
- stream-ocr：视频剪辑使用，提供一些ocr + 专门的图像识别功能
- alist：提供各种的网盘的传输服务

### 1. 创建路径

```shell
mkdir -p /home/admin/stream/{download,config,logs,dump,account,thumbnail}
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

# mysql配置
spring.datasource.url=jdbc:mysql://localhost/stream_move?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=123456

# ocr相关服务
ocr.server.host=127.0.0.1
ocr.server.port=5000
ocr.server.token=xxx

# alist网盘服务
alist.server.host=alist
alist.server.username=admin
alist.server.password=123456
alist.server.port=5244

# 企业微信的配置
wecom.corp.id=wwxx
wecom.agent.id=1000000
wecom.agent.secret=xx
wecom.event.token=xx
wecom.event.encoding-aes-key=xx
wecom.webhook.secret=xx
message.receive.secret=xxx

# 系统表示
system.env.flag=default

# 是否是挂载存储
system.storage.mounted=false
```

填写init.json配置：

```json
{
  "roomCheckCron": "20 2/5 * * * ?",
  "fileCleanCron": "0 5/15 * * * ?",
  "wechatVideoLoginCheckCron": "0 0 0/6 * * ?",
  "configRefreshCron": "0 0/30 * * * ?",
  "maxRecordingCount": 2,
  "videoPartLimitSize": 100,

  "biliCookies": "{b站网页端cookies}",
  "soopUserName": "{soop账号登录用户名}",
  "soopPassword": "{soop账号登录密码}",
  "twitchAuthorization": "{twitch的授权信息, 在请求头中}",
  "taobaoCookies": "{淘宝的ookies}",
  "xhsCookies": "{小红书cookies}",
  "douyuCookies": "{斗鱼cookies}",
  "kuaishouCookies": "{快手cookies}"
}
```

基础配置：

- **roomCheckCron**     必填，主播是否在线的检测corn表达式，最好不要太频繁
- **fileCleanCron**     必填，针对上传完的视频进行定时清理的cron表达式
- **wechatVideoLoginCheckCron** 非必填，微信视频号登录态主动检查和保活cron表达式，默认每6小时
- **configRefreshCron** 必填，刷新从数据库刷新主播配置的cron表达式
- **videoPartLimitSize** 非必填，上传单个视频的最小大小（M）,默认是0
- **maxRecordingCount**  非必填，同时最大的录播主播个数，默认2

录制twitch相关：

- **twitchAuthorization**：twitch的authorization（录制twitch直播最好有）

录制小红书相关：

- **xhsCookies**：小红书的cookies（录制小红书直播最好有）

录制youtube相关：

- **youtubeCookies**：youtube的cookies（录制youtube直播最好有）

录制淘宝直播相关：

- **taobaoCookies**：淘宝的cookies（录制taobaoCookies直播必须有）

录制Soop（原来的AfreecaTV）相关：

- **soopCookies**：Soop的cookies（录制Soop直播最好有）
- **soopUserName**：Soop的账号（录制Soop直播最好有，某些特殊直播必须有）
- **soopPassword**：Soop的密码（录制Soop直播最好有，某些特殊直播必须有）

上传B站（网页端）相关：

- **biliCookies**      B站网页端的cookies值

### 3. 数据库配置

执行init/init-sql.sql创建录播信息表， 字段解释

- name：主播名称
- room_url：直播间地址
- record_type：录制类型, vod为录像，living为直播
- template_title：视频标题模板，支持名称和时间占位符，如：【${name}直播回放】 ${time}
- upload_platforms：上传的平台名称（多平台","分割）
    - BILI_CLIENT：B站客户端上传
    - BILI_WEB：B站网页端上传
    - DOU_YIN：抖音页面自动化上传
    - DOU_YIN_WEB：抖音创作者中心网页链路上传（仅上传 `highlight.mp4`）
    - WECHAT_VIDEO_WEB：微信视频号助手网页链路上传（仅上传 `highlight.mp4`）
    - ALI_PAN: 阿里云盘上传
    - BAIDU_PAN: 百度云盘上传
    - QUARK_PAN: 夸克云盘上传
    - UC_PAN: UC云盘上传
- process_plugins：视频处理插件
    - LOL_HL_VOD_CUT: 英雄联盟直播精彩自动剪辑（效果还行吧...）
- tags：视频标签，支持多个，用逗号隔开
- env：环境标识，默认default
- record_mode：按大小/时间录制视频，t表示时间，s表示大小，如t_3600表示单个录制视频时长1小时，s_4094表示单个录制视频大小4G

抖音网页链路上传（`DOU_YIN_WEB`）：

- 账号登录态文件为 `${sh.account-save.path}/douyin-cookies.json`，格式是 Playwright `storageState` JSON，不是单独复制出来的 Cookie 请求头。
- 首次使用或登录过期时，需要在可见浏览器中打开抖音创作者中心并扫码登录，再保存该文件；`DOU_YIN` 与 `DOU_YIN_WEB` 共用这份登录态。
- 上传器只处理录制目录下精彩剪辑生成的 `highlight.mp4`，封面固定使用该视频的第一帧截图，不读取 `cover_file_path` 或 `work-thumbnail.jpg`。
- Java 负责 AWS4 签名、视频分片和封面文件字节传输；无界面浏览器承载创作者中心安全脚本，以及与浏览器会话绑定的 VOD/ImageX 小型控制面请求和最终 `create_v2` 提交。这里没有页面点击自动化，但并非完全脱离浏览器。
- 作品按本次抓包确认的参数公开发布。标题来自 `template_title`（创作者中心限制 30 字），描述来自 `desc`，话题来自 `tags`；描述和话题合计按页面限制控制在 1000 字以内。
- 相同视频再次提交时，抖音可能返回 `517 / 视频已发布`；上传器将其作为幂等成功处理，避免反复重试。该网页协议不是抖音开放平台的稳定公开 API，网页升级后可能需要重新抓包适配。

微信视频号网页链路上传（`WECHAT_VIDEO_WEB`）：

- 账号登录态文件为 `${sh.account-save.path}/wechat-video-cookies.json`，格式是 Playwright `storageState` JSON。首次使用或登录过期时，需要打开[微信视频号助手](https://channels.weixin.qq.com/platform/post/create)扫码登录并重新保存。
- 上传器只处理录制目录下的 `highlight.mp4`；封面固定从该视频第一帧生成 JPEG，不读取 `cover_file_path` 或默认缩略图。
- Java/OkHttp 按创作者中心当前协议执行 8 MiB 分片、视频和首帧封面上传；无界面浏览器只维持登录与创作者中心安全上下文，并提交定位、裁剪、预检查和发布等小型控制请求。
- 作品按抓包确认的默认新建作品链路公开发布。`template_title` 映射到 `objectDesc.mpTitle`，`desc` 映射到描述，`tags` 同时映射到描述中的 `#话题`、顶层 `topics` 和 `finderTopicInfo`。默认位置使用创作者中心定位接口返回值。
- 本地上传完成标识使用本次请求的 `clientid`，因为创作者中心前端只根据 `errCode == 0` 判断发表成功，并不依赖返回作品 ID。该网页协议不是微信开放平台的稳定公开 API，页面升级后可能需要重新抓包适配。

### 4. alist配置

在配置挂在目录时，对应的**挂载路径**名设置为：

- 百度云盘：/百度网盘
- 阿里云盘：/阿里云盘
- 夸克云盘：/夸克云盘
- UC网盘：/UC网盘
- 天翼云盘：/天翼云盘

### 4. 本地编译

```shell
# 没有maven安装以下：apt install maven
mvn package

# 创建镜像（比较耗时）
docker build -t stream-base:latest -f Dockerfile-base .
docker build -t stream-move:latest -f Dockerfile .

# 启动docker
docker-compose up -d
```

# 登录alist，配置rclone账号


## 参考项目：

https://github.com/ZhangMingZhao1/StreamerHelper

https://github.com/ihmily/DouyinLiveRecorder

https://github.com/AsaChiri/DDRecorder

https://github.com/KLordy/auto_publish_videos

https://github.com/dreammis/social-auto-upload
