#!/usr/bin/env bash
set -euo pipefail
umask 077

# 发行目录必须来自完整验收。不接受任意工作树，不修改整机配置。
# V8 建立预占与在途；旧 V7 应用不理解该资金模型，迁移后不得无条件退回旧应用。
release_dir="${1:?用法：deploy-app-release.sh /绝对路径/发行目录}"
project_dir="${FINCORE_PROJECT_DIR:-/opt/fincore-reliability-lab}"
[[ "$release_dir" == /* && -d "$release_dir" && "$project_dir" == /opt/fincore-reliability-lab ]] || exit 2
[[ "$(id -u)" == 0 ]] || { echo "请通过 sudo 执行项目级发布。" >&2; exit 2; }
command -v docker >/dev/null
command -v jq >/dev/null
command -v flock >/dev/null
exec 9>"$project_dir/.fincore-release.lock"
flock -n 9 || { echo "已有 FinCore 发布进行中。" >&2; exit 2; }
cd "$release_dir"
[[ -f app.jar && -f release.json && -f SHA256SUMS && -f web/index.html && -f .dockerignore ]] || exit 2
sha256sum --check --strict SHA256SUMS
jq -e '.tests.failed == 0 and .tests.errors == 0 and .tests.skipped == 0 and .tests.total >= 112
  and .runtimeEvidence.httpLoad == true and .runtimeEvidence.brokerRecovery == true
  and .runtimeEvidence.databaseRestore == true
  and (.backendCommit | test("^[0-9a-f]{40}$")) and (.frontendCommit | test("^[0-9a-f]{40}$"))
  and .databaseVersion == "8"' release.json >/dev/null
release_id="$(jq -r .releaseId release.json)"
[[ "$release_id" =~ ^[a-zA-Z0-9-]+$ ]] || exit 2
[[ ! -e "$release_dir/backup" ]] || { echo "发行目录已经执行过，禁止覆盖备份。" >&2; exit 2; }
compose=(docker compose --project-name fincore-reliability-lab --project-directory "$project_dir"
  -f "$project_dir/docker-compose.yml" -f "$project_dir/docker-compose.cloud.yml")
app_id="$("${compose[@]}" ps -q app)"
postgres_id="$("${compose[@]}" ps -q postgres)"
nginx_id="$("${compose[@]}" ps -q nginx)"
[[ -n "$app_id" && -n "$postgres_id" && -n "$nginx_id" ]] || exit 2
curl -fsS http://127.0.0.1/health | jq -e '.status == "UP"' >/dev/null
old_image="$(docker inspect "$app_id" --format '{{.Image}}')"
new_image="fincore-verified:$release_id"
mkdir "$release_dir/backup"
# 保存其他容器的 ID 和启动时间，验收发布没有影响旁路服务。
other_containers() {
  docker ps -q | while read -r container; do
    docker inspect "$container" --format '{{.Name}} {{.Id}} {{.State.StartedAt}}'
  done | awk '$1 != "/fincore-reliability-lab-app-1"' | sort
}
other_containers > backup/other-containers.before
docker exec "$postgres_id" pg_dump -U fincore -d fincore -Fc > backup/fincore-before.dump
[[ -s backup/fincore-before.dump ]] || exit 1
tar -C "$project_dir/infra/nginx/html" -czf backup/web-before.tar.gz .
cp "$project_dir/infra/nginx/html/index.html" backup/index.html
if [[ -f "$project_dir/infra/nginx/html/release.json" ]]; then cp "$project_dir/infra/nginx/html/release.json" backup/release.json; fi
printf 'services:\n  app:\n    image: "%s"\n' "$old_image" > backup/rollback-image.yml
printf 'services:\n  app:\n    image: "%s"\n' "$new_image" > release-image.yml
docker build --network=none --pull=false --build-arg "FINCORE_RUNTIME_BASE=$old_image" -f Dockerfile.release -t "$new_image" .
rollback() {
  trap - ERR
  # 新资金状态必须由兼容版本继续处理。失败关闭也不能启动会绕过预占的旧撮合实现。
  current_schema="$(docker exec "$postgres_id" psql -X -U fincore -d fincore -Atc "SELECT count(*) FROM flyway_schema_history WHERE version='8' AND success")"
  previous_schema="$(jq -r '.databaseVersion // "unknown"' backup/release.json 2>/dev/null || echo unknown)"
  if [[ "$current_schema" != 0 && "$previous_schema" != 8 ]]; then
    printf '%s\n' 'FAILED_REQUIRES_V8_COMPATIBLE_APP' > release-status.txt
    "${compose[@]}" stop app
    echo "V8 资金迁移已生效，旧版不兼容；应用已停止接单，保留资金和备份，须修复或使用 V8 兼容版本。" >&2
    return
  fi
  echo "发布失败，回退原应用与首页；保留新增表及所有账务数据。" >&2
  "${compose[@]}" -f "$release_dir/backup/rollback-image.yml" up -d --no-deps --no-build --pull never app || return 1
  cp backup/index.html "$project_dir/infra/nginx/html/index.html.rollback"
  chmod 644 "$project_dir/infra/nginx/html/index.html.rollback"
  mv "$project_dir/infra/nginx/html/index.html.rollback" "$project_dir/infra/nginx/html/index.html"
  if [[ -f backup/release.json ]]; then install -m 0644 backup/release.json "$project_dir/infra/nginx/html/release.json";
  elif [[ -f "$project_dir/infra/nginx/html/release.json" ]]; then mv "$project_dir/infra/nginx/html/release.json" backup/failed-release.json; fi
  docker exec "$nginx_id" nginx -s reload || return 1
  printf '%s\n' 'ROLLED_BACK_CHECK_HEALTH' > release-status.txt
}
trap 'rollback; exit 1' ERR
"${compose[@]}" -f "$release_dir/release-image.yml" up -d --no-deps --no-build --pull never app
docker exec "$nginx_id" nginx -t
docker exec "$nginx_id" nginx -s reload
healthy=false
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1/health | jq -e '.status == "UP"' >/dev/null; then healthy=true; break; fi
  sleep 3
done
[[ "$healthy" == true ]]
docker exec "$postgres_id" psql -X -U fincore -d fincore -Atc "SELECT success FROM flyway_schema_history WHERE version='8'" | grep -qx t
# 先复制内容寻址资源，再原子替换首页；保留旧资源以支持已打开页面和回退。
if [[ -d web/_next ]]; then cp -a web/_next "$project_dir/infra/nginx/html/"; fi
for asset in favicon.svg og.png; do
  if [[ -f "web/$asset" ]]; then install -m 0644 "web/$asset" "$project_dir/infra/nginx/html/$asset"; fi
done
cp web/index.html "$project_dir/infra/nginx/html/index.html.next"
chmod 644 "$project_dir/infra/nginx/html/index.html.next"
mv "$project_dir/infra/nginx/html/index.html.next" "$project_dir/infra/nginx/html/index.html"
find "$project_dir/infra/nginx/html/_next" -type d -exec chmod 755 {} +
find "$project_dir/infra/nginx/html/_next" -type f -exec chmod 644 {} +
install -m 0644 release.json "$project_dir/infra/nginx/html/release.json"
curl -fsS http://127.0.0.1/ >/dev/null
other_containers > backup/other-containers.after
cmp backup/other-containers.before backup/other-containers.after
printf '%s\n' 'DEPLOYED' > release-status.txt
trap - ERR
echo "FinCore 已发布：$release_id；公网地址未改变，其他容器未重启。"
