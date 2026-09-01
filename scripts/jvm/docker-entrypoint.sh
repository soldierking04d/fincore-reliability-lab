#!/usr/bin/env sh
set -eu

# 只允许选择仓库内经过审查的 JVM 配置，避免任意路径注入启动参数。
case "${FINCORE_JVM_PROFILE:-g1}" in
  g1|zgc)
    jvm_profile="${FINCORE_JVM_PROFILE:-g1}"
    ;;
  *)
    echo "Unsupported FINCORE_JVM_PROFILE: ${FINCORE_JVM_PROFILE}" >&2
    exit 64
    ;;
esac

exec java "@/app/config/jvm/${jvm_profile}.options" -jar /app/app.jar
