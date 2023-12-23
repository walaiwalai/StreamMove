
## 介绍
该项目用java实现了一个直播录制工具，实时录制直播保存为视频文件，向B站自动投稿，目前支持的平台有虎牙、斗鱼。代码借鉴了StreamerHelper、DouyinLiveRecorder
等。

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
- roomCheckCron 主播是否在线的检测corn表达式，最好不要太频繁
- videoSavePath 录播的保存地址
- segmentDuration 录播单个视频长度（秒）
- quality 录播质量，原画/超清/高清/标清
- videoPartLimitSize 上传B站的单个视频的最小大小（兆）
- accessToken B站上传的accessToken（下面有说明）
- mid B站上传的身份Id（下面有说明）
- fileCleanCron 针对上传完的视频进行定时清理的cron表达式
- configRefreshCron 针对init.json和streamer.json中的配置重新刷新的cron表达式

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

以上配置借鉴了[StreamerHelper](https://github.com/ZhangMingZhao1/StreamerHelper)


## 关于B站视频上传
- B站Web端视频分P上传需要满足：用户等级大于 3，且粉丝数 > 1000，所以暂不支持web端上传(后续支持)。
- 支持“哔哩哔哩投稿工具”客户端（版本2.3.0.1089）分P上传视频，其中init.json中的accessToken和mid抓包“member.bilibili.com/preupload”这个请求。

## 参考项目：
https://github.com/ZhangMingZhao1/StreamerHelper
https://github.com/ihmily/DouyinLiveRecorder
https://github.com/AsaChiri/DDRecorder