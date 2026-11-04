#!/usr/bin/env bash
# 端到端契约联调：启动本机 PJGoIoT（STORE=fixture），再用 JVM 测试直连验证同步契约。
# 依赖：Go 1.22+、JDK 21、curl、lsof。
# 本地：./scripts/e2e-contract.sh
# CI：PJGOIOT_DIR 指向 checkout 目录，E2E_LOG_DIR 用于把服务日志留给 artifact。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="${PJGOIOT_DIR:-$ROOT/../PJGoIoT}"
LOG_DIR="${E2E_LOG_DIR:-}"
LOG="$(mktemp "${TMPDIR:-/tmp}/pjgoiot-e2e-log.XXXXXX")"
BIN=""
SERVICE_PID=""

cleanup() {
    if [[ -n "$SERVICE_PID" ]] && kill -0 "$SERVICE_PID" 2>/dev/null; then
        kill "$SERVICE_PID" 2>/dev/null || true
        wait "$SERVICE_PID" 2>/dev/null || true
    fi
    # CI 失败时保留 Go 服务日志，供 artifact 上传排查。
    if [[ -n "$LOG_DIR" && -f "$LOG" ]]; then
        mkdir -p "$LOG_DIR"
        cp "$LOG" "$LOG_DIR/go-service.log"
    fi
    [[ -n "$BIN" ]] && rm -f "$BIN"
    [[ -n "$LOG" ]] && rm -f "$LOG"
}
trap cleanup EXIT INT TERM

fail() {
    echo "[E2E] 失败：$*" >&2
    if [[ -s "$LOG" ]]; then
        echo "[E2E] Go 服务日志（最后 60 行）：" >&2
        tail -n 60 "$LOG" >&2
    fi
    exit 1
}

port_in_use() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

command -v go >/dev/null 2>&1 || fail "未找到 go，可安装 Go 1.22+ 后重试"
command -v curl >/dev/null 2>&1 || fail "未找到 curl"
[[ -f "$GO_DIR/go.mod" ]] || fail "未找到 Go 后端：${GO_DIR}（可用 PJGOIOT_DIR 指定）"

# 统一使用 JDK 21，避免本机默认 JDK 过高导致 AGP 失败。
if [[ -x /usr/libexec/java_home ]]; then
    JDK21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$JDK21" ]]; then
        export JAVA_HOME="$JDK21"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

if [[ -n "${E2E_PORT:-}" ]]; then
    PORT="$E2E_PORT"
    port_in_use "$PORT" && fail "指定端口 $PORT 已被占用"
elif ! port_in_use 8080; then
    PORT=8080
elif ! port_in_use 18080; then
    PORT=18080
    echo "[E2E] 端口 8080 已被占用，改用 18080"
else
    fail "端口 8080 与 18080 均被占用，无法启动 fixture 服务"
fi

BASE_URL="http://127.0.0.1:$PORT"
echo "[E2E] 构建 PJGoIoT（${GO_DIR}）"
BIN="$(mktemp "${TMPDIR:-/tmp}/pjgoiot-e2e.XXXXXX")"
(cd "$GO_DIR" && go build -o "$BIN" ./cmd/api) || fail "Go 后端构建失败"

echo "[E2E] 启动 fixture 服务：${BASE_URL}（STORE=fixture）"
(
    cd "$GO_DIR"
    STORE=fixture \
        ADDR="127.0.0.1:$PORT" \
        FIXTURES_DIR="$GO_DIR/fixtures" \
        LOG_LEVEL="${E2E_LOG_LEVEL:-warn}" \
        "$BIN"
) >"$LOG" 2>&1 &
SERVICE_PID=$!

READY=0
for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$BASE_URL/api/v1/health" >/dev/null 2>&1; then
        READY=1
        break
    fi
    if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
        fail "Go 服务启动即退出"
    fi
    sleep 0.5
done
[[ "$READY" -eq 1 ]] || fail "30 秒内 /api/v1/health 未返回 200"

echo "[E2E] 执行 Gradle 契约测试：:app:e2eContract"
set +e
(cd "$ROOT" && ./gradlew --console=plain :app:e2eContract -Pe2eBaseUrl="$BASE_URL")
STATUS=$?
set -e

if [[ "$STATUS" -ne 0 ]]; then
    fail "契约联调用例未通过（exit=$STATUS）"
fi

echo "[E2E] 通过：fixture @ ${BASE_URL}，匿名登录 / push / changes / 幂等重放 / LWW 冲突 / 401 / 跨用户 404 全部通过"
