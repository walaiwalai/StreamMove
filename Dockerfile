FROM stream-base:latest
MAINTAINER caiwen 'caiwenwqc@163.com'

VOLUME /tmp

WORKDIR /home/admin/stream
ENV APP_HOME=/home/admin/stream

COPY sh-config/target/classes/application-prod.properties ${APP_HOME}/application-prod.properties
COPY docker/replace_placeholders.py ${APP_HOME}/replace_placeholders.py
COPY docker/release_disk_space.sh ${APP_HOME}/release_disk_space.sh
COPY sh-start/target/sh-start-1.0-SNAPSHOT.jar ${APP_HOME}/sh-start-1.0-SNAPSHOT.jar

RUN mkdir -p ${APP_HOME}/download ${APP_HOME}/logs ${APP_HOME}/dump ${APP_HOME}/account ${APP_HOME}/thumbnail

# 定时任务
RUN chmod +x ${APP_HOME}/release_disk_space.sh
RUN echo "*/15 * * * * root ${APP_HOME}/release_disk_space.sh >> ${APP_HOME}/logs/release_disk_space.log 2>&1" > /tmp/cron_task

# 追加临时文件内容到 crontab 并重启 cron 服务
RUN cat /tmp/cron_task >> /etc/crontab && rm /tmp/cron_task && service cron restart

# 设置日志文件权限
RUN touch ${APP_HOME}/logs/release_disk_space.log && chmod 644 ${APP_HOME}/logs/release_disk_space.log

# 运行 jar 包和本地shadowsocks
ENTRYPOINT ["sh", "-c", "cron && python3 replace_placeholders.py && java -Dfile.encoding=utf-8 -Duser.timezone=GMT+08 -Dspring.profiles.active=prod -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${APP_HOME}/dump/ -Xms1g -Xmx1g -jar ${APP_HOME}/sh-start-1.0-SNAPSHOT.jar --spring.config.location=file:${APP_HOME}/application-prod.properties"]