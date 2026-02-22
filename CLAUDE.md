# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

StreamerRecord is a Java-based live stream recording and upload system. It records live streams from platforms like Bilibili, Douyin, Huya, Twitch, YouTube, etc., processes the videos (optional plugins like LOL highlight cutting), and uploads to various platforms (Bilibili, Douyin, cloud storage via Alist).

## Build Commands

```bash
# Compile and package all modules
mvn package

# Skip tests during build
mvn package -DskipTests

# Clean build
mvn clean package
```

## Running Locally

The application is a Spring Boot multi-module project with entry point in `sh-start`:

```bash
# Run from the sh-start module
java -jar sh-start/target/sh-start-1.0-SNAPSHOT.jar

# Or with Maven from root
mvn -pl sh-start spring-boot:run
```

Default profile is `dev` (configured in `sh-config/src/main/resources/application.properties`).

## Architecture Overview

### Module Structure

- **sh-start**: Entry point, Spring Boot application launcher
- **sh-engine**: Core business logic (state machine, processors, uploaders, room checkers)
- **sh-schedule**: Quartz-based scheduled workers for cron jobs
- **sh-config**: Configuration, database models, MyBatis mappers, utilities
- **sh-message**: WeCom (WeChat Work) integration for notifications

### State Machine Flow

The recording workflow is implemented as a state machine (`RecordStateMachine`) with these stages:

1. **INIT** → StatusCheckStageProcessor: Initialize recording context
2. **STATUS_CHECK_FINISH** → RoomCheckStageProcessor: Check if streamer is online
3. **ROOM_CHECK_FINISH** → StreamRecordStageProcessor: Record stream using streamlink/ffmpeg
4. **STREAM_RECORD_FINISH** → WorkProcessStageProcessor: Apply video processing plugins
5. **VIDEO_PROCESS_FINISH** → WorkUploadStageProcessor: Upload to configured platforms
6. **VIDEO_UPLOAD_FINISH** → EndStageProcessor: Clean up and mark complete

Each processor extends `AbstractStageProcessor` and implements:
- `acceptState()`: The state it handles
- `targetState()`: The state it transitions to
- `processInternal()`: The actual processing logic

### Key Components

**Room Checkers** (`sh-engine/processor/checker/`): Platform-specific implementations to detect if a streamer is live. Each platform (Bilibili, Douyin, Twitch, etc.) has its own checker extending `AbstractRoomChecker`.

**Uploaders** (`sh-engine/processor/uploader/`): Platform-specific upload implementations including:
- `BiliClientUploader` / `BiliWebUploader`: Bilibili uploads
- `DouyinUploader`: Douyin uploads
- `AliPanUploader`, `BaidunPanUploader`, `QuarkPanUploader`: Cloud storage via Alist

**Danmaku Recording**: Uses `ordinaryroad-live-chat-client` library to record live chat/danmaku from Bilibili, Douyin, Huya, and Douyu.

## Configuration

### Database

MySQL schema defined in `init/init-sql.sql`. Main table `streamer` stores streamer configurations including:
- `room_url`: Live stream URL
- `record_type`: "vod" (record VOD) or "living" (live recording)
- `upload_platforms`: Comma-separated list of upload targets
- `process_plugins`: Video processing plugins (e.g., "LOL_HL_VOD_CUT")
- `record_mode`: "t_3600" (time-based, 1 hour segments) or "s_2048" (size-based, 2GB segments)

### Local Dev Config

Dev configuration in `sh-config/src/main/resources/application.properties`:
- Redis: localhost:6379
- MySQL: localhost:3306/stream_move
- Server: 0.0.0.0:8080 with context path `/api`

### External Dependencies

The application requires external tools:
- **streamlink**: For stream recording
- **ffmpeg**: For video processing
- **playwright**: For browser automation (some uploaders)
- **Alist**: For cloud storage uploads (runs as separate Docker container)
- **Redis**: For caching and distributed state
- **MySQL**: For persistent storage

## Docker Deployment

The application is designed for Docker deployment:

```bash
# Build base image (includes ffmpeg, streamlink, Java 8)
docker build -t stream-base:latest -f Dockerfile-base .

# Build application image
docker build -t stream-move:latest -f Dockerfile .

# Start all services (includes alist)
docker-compose up -d
```

Volumes expected at `/home/admin/stream/`:
- `download/`: Recorded videos
- `config/`: Configuration files (config.properties, init.json)
- `logs/`: Application logs
- `dump/`: Heap dumps on OOM
- `account/`: Account data
- `thumbnail/`: Thumbnail images

## Testing

No test suite is currently configured in this project.

## Important Implementation Details

- **Recording Segments**: Videos are split based on `record_mode` (time or size) to manage large files
- **Streamlink Integration**: Recording uses Python streamlink CLI via `commons-exec` library
- **Danmaku Files**: Recorded as CSV files alongside video files using `opencsv`
- **Proxy Support**: Configurable proxy settings for international platforms like Twitch/YouTube
- **Traffic Control**: Each streamer has `cur_traffic_gb` and `max_traffic_gb` for bandwidth management

## Development Rules

### External Service Integration

**接入外部服务必须严格按照官方文档，严禁瞎猜代码。** 具体规则：

1. **文档优先**：在没有看到官方接入文档、API文档或SDK示例代码之前，不得编写任何接入代码
2. **参数确认**：所有请求参数、Header、鉴权方式必须文档中有明确说明，不能自行推断
3. **URL确认**：API端点URL必须从文档中获取，不能假设或拼接
4. **返回格式**：响应数据的解析必须基于文档定义的返回格式，不能猜测字段含义
5. **异常处理**：错误码和异常场景的处理必须参考文档说明

违反规则的情况包括但不限于：
- 假设某个云服务的API格式与竞品相同
- 根据URL路径猜测参数传递方式
- 根据SDK名称推断方法签名
- 没有文档依据时假设返回JSON结构

如果你无法访问文档，请明确告知用户：**"无法获取文档，请提供以下信息：1. ... 2. ..."**，列出需要的具体参数，等待用户提供后再编写代码。
