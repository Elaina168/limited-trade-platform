#!/bin/bash
set -euo pipefail

# 使用基础镜像已有的Bash检查HTTP响应，不额外安装curl。
exec 3<>/dev/tcp/127.0.0.1/8080
printf 'GET /api/ping HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
head -n 1 <&3 | grep -Eq '^HTTP/1\.[01] 200( |$)'
