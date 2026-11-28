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
ORIG_GO_DIR="$GO_DIR"
PINNED_DIR=""
STATE_DIR=""
LOG_DIR="${E2E_LOG_DIR:-}"
STACK=""
SEED=""

fail() {
    echo "[E2E:pg] 失败：$*" >&2
    exit 1
}

cleanup() {
    local rc=$?
    # 失败时保留 API 日志，供 CI artifact 上传排查。
    if [[ $rc -ne 0 && -n "$LOG_DIR" && -n "$STATE_DIR" && -f "$STATE_DIR/api.log" ]]; then
        mkdir -p "$LOG_DIR"
        cp "$STATE_DIR/api.log" "$LOG_DIR/postgres-api.log"
    fi
    if [[ -n "$STACK" ]]; then
        (cd "$GO_DIR" && bash "$STACK" --stop) >/dev/null 2>&1 || true
    fi
    if [[ -n "$PINNED_DIR" ]]; then
        git -C "$ORIG_GO_DIR" worktree remove --force "$PINNED_DIR" >/dev/null 2>&1 \
            || rm -rf "$PINNED_DIR"
    fi
    exit "$rc"
}
trap cleanup EXIT INT TERM

# 把后端物化到固定 tag 上：HEAD 已在该 tag 时直接用，否则建临时 worktree。
# CI 只 checkout 默认分支，因此不能依赖调用方手动切 tag。结果写入 GO_DIR / PINNED_DIR。
resolve_backend_dir() {
    local src="$1" ref="$2" want have tmp
    git -C "$src" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
        || fail "无法确认后端版本：${src} 不是 git 仓库（期望 ${ref}）"
    want="$(git -C "$src" rev-parse --verify --quiet "${ref}^{commit}" || true)"
    if [[ -z "$want" ]]; then
        echo "[E2E:pg] 本地缺少 ${ref}，尝试 fetch" >&2
        git -C "$src" fetch --force --depth=1 origin "refs/tags/${ref}:refs/tags/${ref}" >/dev/null 2>&1 || true
        want="$(git -C "$src" rev-parse --verify --quiet "${ref}^{commit}" || true)"
    fi
    [[ -n "$want" ]] || fail "后端缺少标签 ${ref}，请先执行：git -C ${src} fetch --tags"
    have="$(git -C "$src" rev-parse HEAD)"
    if [[ "$have" == "$want" ]]; then
        GO_DIR="$src"
        PINNED_DIR=""
        return 0
    fi
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/pjgoiot-${ref//[^A-Za-z0-9._-]/_}.XXXXXX")"
    echo "[E2E:pg] 后端 HEAD=${have:0:12} 不在 ${ref}，使用临时 worktree：${tmp}" >&2
    git -C "$src" worktree add --detach "$tmp" "$want" >/dev/null 2>&1 \
        || fail "无法创建 ${ref} 的临时 worktree：${tmp}"
    GO_DIR="$tmp"
    PINNED_DIR="$tmp"
}

command -v docker >/dev/null 2>&1 || fail "未找到 docker"
docker info >/dev/null 2>&1 || fail "Docker daemon 不可用"
command -v go >/dev/null 2>&1 || fail "未找到 go"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"
command -v python3 >/dev/null 2>&1 || fail "未找到 python3"

# 后端版本 pin：fixture / Postgres 两条链必须跑同一固定版本。
BACKEND_REF="${E2E_BACKEND_REF:-v0.27.0}"
BACKEND_VERSION="${BACKEND_REF#v}"
resolve_backend_dir "$ORIG_GO_DIR" "$BACKEND_REF"
STATE_DIR="${E2E_STATE_DIR:-$GO_DIR/.e2e}"
STACK="$GO_DIR/scripts/stack-for-e2e.sh"
SEED="$GO_DIR/scripts/seed-for-e2e.sh"
[[ -f "$STACK" ]] || fail "未找到 ${STACK}（可用 PJGOIOT_DIR 指定后端仓库）"
[[ -f "$SEED" ]] || fail "未找到 ${SEED}"
echo "[E2E:pg] 后端版本：${BACKEND_REF}"

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
(cd "$ROOT" && ./gradlew --console=plain :app:e2ePostgres \
    -Pe2eBaseUrl="$BASE_URL" \
    -Pe2eBackendVersion="$BACKEND_VERSION")
STATUS=$?
set -e
[[ "$STATUS" -eq 0 ]] || fail "Postgres 契约套件未通过（exit=${STATUS}）"

echo "[E2E:pg] ---- presence 在线 / 离线 / 从未上报 ----"
presence_login="$(curl -fsS -X POST "$BASE_URL/api/v1/auth/anonymous")" || fail "presence 匿名登录失败"
PRESENCE_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$presence_login")"
PRESENCE_USER_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["userId"])' <<<"$presence_login")"
ONLINE_MAC="AA:BB:CC:00:00:0B"
ONLINE_MAC_LOWER="$(printf '%s' "$ONLINE_MAC" | tr '[:upper:]' '[:lower:]')"
NEVER_MAC="AA:BB:CC:00:00:0C"

presence_payload="$(python3 - "$ONLINE_MAC" "$NEVER_MAC" <<'PY'
import datetime as dt
import json
import sys
import uuid

now = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
online, never = sys.argv[1], sys.argv[2]
devices = [
    {
        "id": str(uuid.uuid4()),
        "platform": "android",
        "externalId": mac,
        "mac": mac.lower(),
        "updatedAt": now,
    }
    for mac in (online, never)
]
readings = [
    {
        "id": str(uuid.uuid4()),
        "platform": "android",
        "externalId": online,
        "metric": "temperature",
        "value": 21.0,
        "unit": "°C",
        "recordedAt": now,
    }
]
print(json.dumps({"devices": devices, "readings": readings}))
PY
)" || fail "构造 presence 载荷失败"

presence_push="$(curl -sS -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/sync/push" \
    -H "Authorization: Bearer $PRESENCE_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: e2e-pg-presence-$(date +%s)" \
    -d "$presence_payload")"
PRESENCE_PUSH_CODE="${presence_push##*$'\n'}"
[[ "$PRESENCE_PUSH_CODE" == "200" ]] || fail "presence push 失败（HTTP ${PRESENCE_PUSH_CODE}）"

# 断言 /my/devices 中指定设备的 presence 与 lastSeenAt 形态。
assert_presence() {
    local body="$1" mac="$2" expected="$3" seen="$4"
    python3 -c '
import json, sys
body, mac, expected, seen = sys.argv[1:5]
items = (json.loads(body).get("items") or [])
device = next((d for d in items if d.get("externalId") == mac), None)
if device is None:
    raise SystemExit("缺少设备 %s" % mac)
if device.get("presence") != expected:
    raise SystemExit("%s presence 期望 %s，实际 %r" % (mac, expected, device.get("presence")))
last = device.get("lastSeenAt")
if seen == "not-null" and not last:
    raise SystemExit("%s lastSeenAt 期望非空，实际 %r" % (mac, last))
if seen == "null" and last is not None:
    raise SystemExit("%s lastSeenAt 期望 null，实际 %r" % (mac, last))
print("%s presence=%s lastSeenAt=%r" % (mac, device.get("presence"), last))
' "$body" "$mac" "$expected" "$seen" || fail "presence 断言失败：${mac}"
}

presence_devices="$(curl -fsS "$BASE_URL/api/v1/my/devices" \
    -H "Authorization: Bearer $PRESENCE_TOKEN")" || fail "读取 /my/devices 失败"
assert_presence "$presence_devices" "$ONLINE_MAC" online not-null
assert_presence "$presence_devices" "$NEVER_MAC" unknown null

# 把 last_seen_at 回拨 20 分钟（超出 900s 窗口）：presence 必须变 offline，
# 但 lastSeenAt 仍保留，供客户端展示「最近上报 X」。
docker exec "$PG_CONTAINER" psql -U pjgoiot -d pjgoiot_e2e -c \
    "UPDATE user_devices SET last_seen_at = now() - interval '20 minutes' WHERE user_id = '${PRESENCE_USER_ID}' AND mac = '${ONLINE_MAC_LOWER}'" \
    >/dev/null || fail "回拨 last_seen_at 失败"

presence_devices="$(curl -fsS "$BASE_URL/api/v1/my/devices" \
    -H "Authorization: Bearer $PRESENCE_TOKEN")" || fail "回拨后读取 /my/devices 失败"
assert_presence "$presence_devices" "$ONLINE_MAC" offline not-null
echo "[E2E:pg] presence 校验通过：online → offline（超窗）/ 从未上报 unknown"

echo "[E2E:pg] ---- 410 RESYNC_REQUIRED 分支 ----"
login="$(curl -fsS -X POST "$BASE_URL/api/v1/auth/anonymous")" || fail "匿名登录失败"
TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' <<<"$login")"
USER_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["userId"])' <<<"$login")"

# 结构变更（household / room）先写入，保证过期的那条是结构变更，用于验证「结构缺口触发 410」。
home_json="$(curl -fsS -X POST "$BASE_URL/api/v1/households" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"e2e-410-home"}')" || fail "创建家庭失败"
HOME_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$home_json")"

room_json="$(curl -fsS -X POST "$BASE_URL/api/v1/households/$HOME_ID/rooms" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"e2e-410-room","sortOrder":0}')" || fail "创建房间失败"

rename_result="$(curl -sS -w '\n%{http_code}' -X PATCH "$BASE_URL/api/v1/households/$HOME_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name":"e2e-410-renamed"}')"
RENAME_CODE="${rename_result##*$'\n'}"
[[ "$RENAME_CODE" == "200" ]] || fail "家庭改名失败（HTTP ${RENAME_CODE}）"

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
STRUCT_OLDEST_SEQ="$(docker exec "$PG_CONTAINER" psql -U pjgoiot -d pjgoiot_e2e -tAc \
    "SELECT MIN(seq) FROM change_log WHERE user_id = '${USER_ID}' AND entity IN ('household','room')" | tr -d '[:space:]')"
[[ "$STRUCT_OLDEST_SEQ" =~ ^[0-9]+$ ]] || fail "读取最老结构变更 seq 失败：${STRUCT_OLDEST_SEQ}"
[[ "$STRUCT_OLDEST_SEQ" == "$OLDEST_SEQ" ]] || fail "最老变更不是结构变更（struct=${STRUCT_OLDEST_SEQ} all=${OLDEST_SEQ}）"
echo "[E2E:pg] 用户 ${USER_ID} 最老结构变更 seq=${STRUCT_OLDEST_SEQ}（也是整体最老），把 server_ts 改到 100 天前"

docker exec "$PG_CONTAINER" psql -U pjgoiot -d pjgoiot_e2e -c \
    "UPDATE change_log SET server_ts = now() - interval '100 days' WHERE user_id = '${USER_ID}' AND seq = ${STRUCT_OLDEST_SEQ}" \
    >/dev/null || fail "SQL 改写 server_ts 失败"

echo "[E2E:pg] 运行 cleanup（change_log 保留 90 天）"
(cd "$GO_DIR" && DATABASE_URL="$DATABASE_URL" go run ./cmd/cleanup \
    -retention-days 3650 \
    -change-log-retention-days 90 \
    -idempotency-retention-days 7) || fail "cleanup 执行失败"

OLD_CURSOR=$((STRUCT_OLDEST_SEQ - 1))
((OLD_CURSOR > 0)) || fail "旧游标为 0，无法验证 410（结构变更应先于设备变更）"

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
changes = body.get("changes") or []
if not changes:
    raise SystemExit("cursor=0 全量重拉应返回剩余变更")
entities = {c.get("entity") for c in changes}
if not {"household", "room"} & entities:
    raise SystemExit("cursor=0 重拉应包含 household/room 结构变更，实际 entities=%r" % sorted(entities))
print("cursor=0 changes=%d nextCursor=%s entities=%r" % (
    len(changes), body.get("nextCursor"), sorted(entities)))
' "$BODY" || fail "cursor=0 全量重拉内容异常"
echo "[E2E:pg] cursor=0 从未返回 410，剩余结构变更已拉回"

echo "[E2E:pg] 通过：postgres @ ${BASE_URL}"
echo "[E2E:pg] 覆盖：匿名登录/expiresAt → push 设备+读数 → changes/limit → presence 三态（online/offline/unknown）→ 结构缺口 410 全量重拉（household/room）→ series/metrics → export/limit → 注销 401"
