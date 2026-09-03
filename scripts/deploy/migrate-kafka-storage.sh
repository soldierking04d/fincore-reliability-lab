#!/usr/bin/env bash
set -euo pipefail
umask 077

# 维护窗口专项：只迁移本项目 Kafka，先停写、留一致备份与恢复镜像，禁止删除原始备份。
# 普通发布不能自动调用；须由操作者明确批准短暂停止 FinCore app 和 Kafka。
[[ "${1:-}" == --execute-approved-maintenance ]] || {
  echo "需要已批准的 FinCore app/Kafka 维护窗口；默认不执行迁移。" >&2; exit 2;
}
release_dir="${2:?需要已验收的发行目录}"
project_dir=/opt/fincore-reliability-lab
[[ "$(id -u)" == 0 && "$release_dir" =~ ^/opt/fincore-releases/[a-zA-Z0-9-]+$ ]] || exit 2
cd "$release_dir"
sha256sum --check --strict SHA256SUMS
jq -e '.runtimeEvidence.kafkaVolumeMigration == true' release.json >/dev/null
release_id="$(jq -r .releaseId release.json)"
[[ "$release_id" =~ ^[a-zA-Z0-9-]+$ ]] || exit 2
exec 9>"$project_dir/.fincore-release.lock"
flock -n 9 || { echo "已有 FinCore 发布或迁移进行中。" >&2; exit 2; }
compose=(docker compose --project-name fincore-reliability-lab --project-directory "$project_dir"
  -f "$project_dir/docker-compose.yml" -f "$project_dir/docker-compose.cloud.yml")
managed="$project_dir/docker-compose.kafka-managed.json"
[[ ! -e "$managed" && ! -e kafka-migration ]] || {
  echo "已有迁移配置或备份，须人工检查，不能重复创建或覆盖。" >&2; exit 2;
}
kafka_id="$("${compose[@]}" ps -q kafka)"
app_id="$("${compose[@]}" ps -q app)"
nginx_id="$("${compose[@]}" ps -q nginx)"
postgres_id="$("${compose[@]}" ps -q postgres)"
for identifier in "$kafka_id" "$app_id" "$nginx_id" "$postgres_id"; do
  [[ "$identifier" =~ ^[a-f0-9]{64}$ ]] || exit 2
done
[[ "$(docker inspect "$kafka_id" --format '{{index .Config.Labels "com.docker.compose.project"}}')" == fincore-reliability-lab ]] || exit 2
old_image="$(docker inspect "$kafka_id" --format '{{.Image}}')"
image="$(jq -r .kafkaImage release.json)"
[[ "$image" =~ ^apache/kafka@sha256:[a-f0-9]{64}$ ]] || exit 2
[[ "$(docker image inspect "$image" --format '{{.Id}}')" == "$old_image" ]] || {
  echo "验收 Kafka 镜像与现网不同；迁移不得兼做 Broker 升级。" >&2; exit 2;
}
# 运行时管理接口确认真正的日志目录，不根据 Docker 的 Mounts 猜测。
runtime_dirs="$(docker exec "$kafka_id" /opt/kafka/bin/kafka-log-dirs.sh --bootstrap-server localhost:9092 --describe | tail -n 1)"
old_dir="$(jq -er '.brokers | select(length==1) | .[0].logDirs | select(length==1) | .[0] | select(.error==null) | .logDir' <<< "$runtime_dirs")"
[[ "$old_dir" == /tmp/kafka-logs ]] || { echo "本迁移仅覆盖已核实的 /tmp/kafka-logs，其他布局须重新审查。" >&2; exit 2; }
mkdir kafka-migration
backup="$release_dir/kafka-migration"
other_containers() {
  docker ps -q | while read -r id; do docker inspect "$id" --format '{{.Name}} {{.Id}} {{.State.StartedAt}}'; done |
    awk '$1 != "/fincore-reliability-lab-app-1" && $1 != "/fincore-reliability-lab-kafka-1"' | sort
}
offsets() {
  docker exec "$1" /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --time -1 |
    awk -F: '$1 !~ /^__/ && NF==3 {print}' | sort
}
other_containers > "$backup/other.before"
docker inspect "$kafka_id" > "$backup/container.before.json"
printf '%s\n' "$runtime_dirs" > "$backup/log-dirs.before.json"
old_app_image="$(docker inspect "$app_id" --format '{{.Image}}')"
jq -n --arg image "$old_app_image" '{services:{app:{image:$image}}}' > "$backup/old-app.json"
docker exec "$postgres_id" pg_dump -U fincore -d fincore -Fc > "$backup/fincore-before.dump"
[[ -s "$backup/fincore-before.dump" ]]
"${compose[@]}" stop app
offsets "$kafka_id" > "$backup/offsets.before"
[[ -s "$backup/offsets.before" ]] || { echo "未获得任何业务 Topic 位点，停止迁移。" >&2; exit 1; }
"${compose[@]}" stop kafka
mkdir "$backup/kafka-data"
docker cp "$kafka_id:$old_dir/." "$backup/kafka-data/"
[[ -s "$backup/kafka-data/meta.properties" ]]
(cd "$backup/kafka-data" && find . -type f -print0 | sort -z | xargs -0 sha256sum) > "$backup/data.SHA256SUMS"
recovery_image="fincore-kafka-recovery:$release_id"
docker commit --pause=false "$kafka_id" "$recovery_image" > "$backup/recovery-image.txt"
volume="fincore-kafka-data-$release_id"
if docker volume inspect "$volume" >/dev/null 2>&1; then echo "目标卷已存在，拒绝覆盖。" >&2; exit 2; fi
docker volume create --label fincore.purpose=preserved-kafka "$volume" > "$backup/volume.txt"
docker run --rm --network none --user 0 --entrypoint /bin/sh \
  -v "$backup/kafka-data:/source:ro" -v "$volume:/target" "$image" -ec \
  'test -z "$(ls -A /target)"; cp -a /source/. /target/; test -s /target/meta.properties'
docker run --rm --network none --user 0 --entrypoint /bin/sh \
  -v "$backup:/proof:ro" -v "$volume:/target:ro" "$image" -ec \
  'cd /target; sha256sum --check --strict /proof/data.SHA256SUMS' > "$backup/volume-verification.txt"
jq -n --arg image "$image" --arg volume "$volume" \
  '{services:{kafka:{image:$image,environment:{KAFKA_LOG_DIRS:"/var/lib/kafka/data"},volumes:[{type:"volume",source:"fincore-kafka-preserved",target:"/var/lib/kafka/data"}]}},volumes:{"fincore-kafka-preserved":{external:true,name:$volume}}}' > "$backup/managed.json"
install -m 0600 "$backup/managed.json" "$managed"
printf '%s\n' PREPARED > "$backup/status.txt"
# 此后的自动失败回退仅恢复 Kafka 写层快照；应用仍停止，避免恢复过程中接受新金融请求。
rollback() {
  trap - ERR
  jq -n --arg image "$recovery_image" --arg dir "$old_dir" \
    '{services:{kafka:{image:$image,environment:{KAFKA_LOG_DIRS:$dir}}}}' > "$backup/rollback.json"
  "${compose[@]}" -f "$managed" -f "$backup/rollback.json" up -d --no-deps --no-build --pull never kafka || true
  printf '%s\n' FAILED_APP_STOPPED_REVIEW_REQUIRED > "$backup/status.txt"
  echo "迁移未验收：已尝试恢复旧 Kafka，FinCore 应用保留停止；须核对后再接单，禁止自动覆盖数据库。" >&2
}
trap 'rollback; exit 1' ERR
"${compose[@]}" -f "$managed" up -d --no-deps --no-build --pull never kafka
new_kafka="$("${compose[@]}" -f "$managed" ps -q kafka)"
ready=false
for attempt in $(seq 1 40); do
  if docker exec "$new_kafka" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list > "$backup/topics.after" 2>/dev/null; then ready=true; break; fi
  sleep 3
done
[[ "$ready" == true ]]
offsets "$new_kafka" > "$backup/offsets.after"
cmp "$backup/offsets.before" "$backup/offsets.after"
docker exec "$new_kafka" /opt/kafka/bin/kafka-log-dirs.sh --bootstrap-server localhost:9092 --describe | tail -n 1 > "$backup/log-dirs.after.json"
jq -e '.brokers[0].logDirs | length==1 and .[0].logDir=="/var/lib/kafka/data" and .[0].error==null' "$backup/log-dirs.after.json" >/dev/null
"${compose[@]}" -f "$managed" -f "$backup/old-app.json" up -d --no-deps --no-build --pull never app
docker exec "$nginx_id" nginx -s reload
healthy=false
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1/health | jq -e '.status=="UP"' >/dev/null; then healthy=true; break; fi
  sleep 3
done
[[ "$healthy" == true ]]
other_containers > "$backup/other.after"
cmp "$backup/other.before" "$backup/other.after"
printf '%s\n' MIGRATED_VERIFIED > "$backup/status.txt"
trap - ERR
echo "Kafka 日志已进入显式持久卷，业务 Topic 位点一致，其他项目未重启。"
