#!/usr/bin/env bash
# 真 Postgres 契约联调：
#   起栈（PJGoIoT scripts/stack-for-e2e.sh）→ seed → 跑 JVM 契约套件 →
#   410 RESYNC_REQUIRED 分支（SQL 把最老 seq 的 server_ts 改到 100 天前 + cleanup）→ 停栈。
#
# 依赖：Docker、Go 1.22+、JDK 21、curl、python3。
# 本地：./scripts/e2e-postgres.sh
# CI：PJGOIOT_DIR 指向 checkout 目录，E2E_LOG_DIR 用于失败时保留服务日志。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="${PJGOIOT_DIR:-$ROOT/../PJGoIoT}"
STATE_DIR="${E2E_STATE_DIR:-$GO_DIR/.e2e}"
LOG_DIR="${E2E_LOG_DIR:-}"
STACK="$GO_DIR/scripts/stack-for-e2e.sh"
SEED="$GO_DIR/scripts/seed-for-e2e.sh"

fail() {
    echo "[E2E:pg] 失败：$*" >&2
    exit 1
}

cleanup() {
    local rc=$?
    # 失败时保留 API 日志，供 CI artifact 上传排查。
    if [[ $rc -ne 0 && -n "$LOG_DIR" && -f "$STATE_DIR/api.log" ]]; then
        mkdir -p "$LOG_DIR"
        cp "$STATE_DIR/api.log" "$LOG_DIR/postgres-api.log"
    fi
    (cd "$GO_DIR" && bash "$STACK" --stop) >/dev/null 2>&1 || true
    exit "$rc"
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null 2>&1 || fail "未找到 docker"
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"
command -v go >/dev/null 2>&1 || fail "未找到 go"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"
command -v python3 >/dev/null 2>&1 || fail "未找到 python3"
[[ -f "$STACK" ]] || fail "未找到 ${STACK}（可用 PJGOIOT_DIR 指定后端仓库）"
[[ -f "$SEED" ]] || fail "未找到 ${SEED}"

# 统一使用 JDK 21，避免本机默认 JDK 过高导致 AGP 失败。
if [[ -x /usr/libexec/java_home ]]; then
    JDK21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$JDK21" ]]; then
        export JAVA_HOME="$JDK21"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

# 防止残留栈影响：先停一次（没有栈时该命令是 no-op）。
(cd "$GO_DIR" && bash "$STACK" --stop) >/dev/null 2>&1 || true

echo "[E2E:pg] 启动真实 Postgres + API 栈"
STACK_OUT="$(cd "$GO_DIR" && bash "$STACK")" || fail "栈启动失败"
BASE_URL="$(printf '%s\n' "$STACK_OUT" | sed -n 's/^BASE_URL=//p' | tail -n 1)"
[[ -n "$BASE_URL" ]] || fail "未从栈输出解析到 BASE_URL"
echo "[E2E:pg] BASE_URL=${BASE_URL}"

# shellcheck disable=SC1090,SC1091
source "$STATE_DIR/stack.env"

echo "[E2E:pg] 写入 seed 数据"
bash "$SEED" || fail "seed 失败"

echo "[E2E:pg] 执行 Gradle 契约套件：:app:e2ePostgres"
set +e
(cd "$ROOT" && ./gradlew --console=plain :app:e2ePostgres -Pe2eBaseUrl="$BASE_URL")
STATUS=$?
set -e
[[ "$STATUS" -eq 0 ]] || fail "Postgres 契约套件未通过（exit=$STATUS）"

echo "[E2E:pg] ---- 410 RESYNC_REQUIRED 分支 ----"
login="$(curl -fsS -X POST "$BASE_URL/api/v1/auth/anonymous")" || fail "匿名登录失败"
TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$login")"
USER_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["userId"])' <<<"$login")"

payload="$(python3 - <<'PY'
import datetime as dt
import json
import uuid

now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
devices = []
for idx, mac in ((1, "AA:BB:CC:00:00:66"), (2, "AA:BB:CC:00:00:67")):
    devices.append({
        "id": str(uuid.uuid4()),
        "platform": "android",
        "externalId": mac,
        "mac": mac.lower(),
        "updatedAt": now,
    })
print(json.dumps({"devices": devices, "readings": []}))
PY
)" || fail "构造 push 载荷失败"

push_result="$(curl -sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/sync/push" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: e2e-pg-410-$(date +%s)" \
    -d "$payload")"
PUSH_CODE="${push_result##*$'\n'}"
[[ "$PUSH_CODE" == "200" ]] || fail "push 失败（HTTP ${PUSH_CODE}）"

OLDEST_SEQ="$(docker exec "$PG_CONTAINER" psql -U pjgoiot -d pjgoiot_e2e -tAc \
    "SELECT MIN(seq) FROM change_log WHERE user_id = '${USER_ID}'" | tr -d '[:space:]')"
[[ "$OLDEST_SEQ" =~ ^[0-9]+$ ]] || fail "读取最老 seq 失败：${OLDEST_SEQ}"
echo "[E2E:pg] 用户 ${USER_ID} 最老 seq=${OLDEST_SEQ}，把 server_ts 改到 100 天前"

docker exec "$PG_CONTAINER" psql -U pjgoiot -d pjgoiot_e2e -c \
    "UPDATE change_log SET server_ts = now() - interval '100 days' WHERE user_id = '${USER_ID}' AND seq = ${OLDEST_SEQ}" \
    >/dev/null || fail "SQL 改写 server_ts 失败"

echo "[E2E:pg] 运行 cleanup（change_log 保留 90 天）"
(cd "$GO_DIR" && DATABASE_URL="$DATABASE_URL" go run ./cmd/cleanup \
    -retention-days 3650 \
    -change-log-retention-days 90 \
    -idempotency-retention-days 7) || fail "cleanup 执行失败"

OLD_CURSOR=$((OLDEST_SEQ - 1))
((OLD_CURSOR > 0)) || fail "旧游标为 0，无法验证 410（seed 应先产生更早的 seq）"

resp="$(curl -sS -w '\n%{http_code}' "$BASE_URL/api/v1/sync/changes?cursor=${OLD_CURSOR}" \
    -H "Authorization: Bearer $TOKEN")"
CODE="${resp##*$'\n'}"
BODY="${resp%$'\n'*}"
[[ "$CODE" == "410" ]] || fail "期望 410，实际 ${CODE}：${BODY}"
python3 -c '
import json, sys
body = json.loads(sys.argv[1])
code = (body.get("error") or {}).get("code")
if code != "RESYNC_REQUIRED":
    raise SystemExit("error.code 期望 RESYNC_REQUIRED，实际 %r" % code)
' "$BODY" || fail "410 响应缺少 RESYNC_REQUIRED"
echo "[E2E:pg] 410 校验通过：cursor=${OLD_CURSOR} → RESYNC_REQUIRED"

resp="$(curl -sS -w '\n%{http_code}' "$BASE_URL/api/v1/sync/changes?cursor=0" \
    -H "Authorization: Bearer $TOKEN")"
CODE="${resp##*$'\n'}"
BODY="${resp%$'\n'*}"
[[ "$CODE" == "200" ]] || fail "cursor=0 期望 200，实际 ${CODE}：${BODY}"
python3 -c '
import json, sys
body = json.loads(sys.argv[1])
if not (body.get("changes") or []):
    raise SystemExit("cursor=0 全量重拉应返回剩余变更")
print("cursor=0 changes=%d nextCursor=%s" % (len(body["changes"]), body.get("nextCursor")))
' "$BODY" || fail "cursor=0 全量重拉内容异常"
echo "[E2E:pg] cursor=0 从未返回 410，剩余变更已拉回"

echo "[E2E:pg] 通过：postgres @ ${BASE_URL}"
echo "[E2E:pg] 覆盖：匿名登录/expiresAt → push 设备+读数 → changes/limit → 410 全量重拉 → series/metrics → export/limit → 注销 401"
