#!/bin/bash
set -euo pipefail

# 配置模型密钥时自动启用助手，显式开关优先。
if [[ -z ${AGENT_ENABLED:-} ]]; then
    AGENT_ENABLED=false
    if [[ -n ${DEEPSEEK_API_KEY:-} ]]; then AGENT_ENABLED=true; fi
fi
export AGENT_ENABLED
exec java -jar /app/app.jar "$@"
