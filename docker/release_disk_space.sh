#!/bin/bash

# 记录脚本开始前的磁盘使用情况
start_used_space=$(df -h / | awk 'NR==2 {print $3}')

# 找出包含关键字 "sh-start" 且为真正 Java 进程的 PID
pids=$(ps -ef | awk '$8 == "java" && /sh-start/ {print $2}')

# 遍历每个 PID
for pid in $pids; do
    echo "处理进程 PID: $pid"

    # 进入 /proc/{pid}/fd 目录
    fd_dir="/proc/$pid/fd"
    if [ -d "$fd_dir" ]; then
        # 找出被删除的文件句柄
        deleted_fds=$(ls -l $fd_dir | grep deleted | awk '{print $9}')

        # 遍历每个被删除的文件句柄
        for fd in $deleted_fds; do
            # 执行 echo > /proc/{pid}/fd/{文件句柄} 来释放磁盘空间
            echo > "/proc/$pid/fd/$fd"
        done
    else
        echo "进程 $pid 的文件描述符目录 $fd_dir 不存在"
    fi
done

# 记录脚本结束后的磁盘使用情况
end_used_space=$(df -h / | awk 'NR==2 {print $3}')

# 计算释放的空间
start_used_space_num=$(echo $start_used_space | sed 's/[^0-9.]//g')
end_used_space_num=$(echo $end_used_space | sed 's/[^0-9.]//g')
unit=$(echo $start_used_space | sed 's/[0-9.]*//')
freed_space=$(echo "$start_used_space_num - $end_used_space_num" | bc)

# 打印释放的空间
echo "总共释放了 $freed_space$unit 磁盘空间"