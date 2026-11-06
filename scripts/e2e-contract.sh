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
RATE_LOG="$(mktemp "${TMPDIR:-/tmp}/pjgoiot-e2e-ratelimit.XXXXXX")"
BIN=""
SERVICE_PID=""
RATE_PID=""

cleanup() {
    for pid in "$SERVICE_PID" "$RATE_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
    # CI 失败时保留 Go 服务日志，供 artifact 上传排查。
    if [[ -n "$LOG_DIR" && -f "$LOG" ]]; then
        mkdir -p "$LOG_DIR"
        cp "$LOG" "$LOG_DIR/go-service.log"
    fi
    if [[ -n "$LOG_DIR" && -f "$RATE_LOG" ]]; then
        mkdir -p "$LOG_DIR"
        cp "$RATE_LOG" "$LOG_DIR/go-service-ratelimit.log"
    fi
    [[ -n "$BIN" ]] && rm -f "$BIN"
    [[ -n "$LOG" ]] && rm -f "$LOG"
    [[ -n "$RATE_LOG" ]] && rm -f "$RATE_LOG"
}
trap cleanup EXIT INT TERM

fail() {
    echo "[E2E] 失败：$*" >&2
    for file in "$LOG" "$RATE_LOG"; do
        if [[ -s "$file" ]]; then
            echo "[E2E] Go 服务日志（$file，最后 60 行）：" >&2
            tail -n 60 "$file" >&2
        fi
    done
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

# 反馈限流专属实例：账号 3/min、burst 3，第四个请求必然 429。
# 与主实例分离，避免常规 e2e 的多次反馈提交互相干扰。
RATE_PORT=""
for offset in $(seq 1 20); do
    candidate=$((PORT + offset))
    if ! port_in_use "$candidate"; then
        RATE_PORT="$candidate"
        break
    fi
done
[[ -n "$RATE_PORT" ]] || fail "找不到可用的限流 fixture 端口（从 $((PORT + 1)) 起）"
RATE_BASE_URL="http://127.0.0.1:$RATE_PORT"

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
        RATE_LIMIT_PER_MINUTE="${E2E_RATE_LIMIT_PER_MINUTE:-600}" \
        RATE_LIMIT_BURST="${E2E_RATE_LIMIT_BURST:-600}" \
        "$BIN"
) >"$LOG" 2>&1 &
SERVICE_PID=$!

echo "[E2E] 启动低限流 fixture 服务：${RATE_BASE_URL}（反馈 3/min，burst 3）"
(
    cd "$GO_DIR"
    STORE=fixture \
        ADDR="127.0.0.1:$RATE_PORT" \
        FIXTURES_DIR="$GO_DIR/fixtures" \
        LOG_LEVEL="${E2E_LOG_LEVEL:-warn}" \
        RATE_LIMIT_PER_MINUTE="${E2E_RATE_LIMIT_PER_MINUTE:-600}" \
        RATE_LIMIT_BURST="${E2E_RATE_LIMIT_BURST:-600}" \
        FEEDBACK_USER_RATE_PER_MINUTE=3 \
        FEEDBACK_USER_BURST=3 \
        FEEDBACK_IP_RATE_PER_MINUTE=30 \
        FEEDBACK_IP_BURST=30 \
        "$BIN"
) >"$RATE_LOG" 2>&1 &
RATE_PID=$!

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

RATE_READY=0
for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$RATE_BASE_URL/api/v1/health" >/dev/null 2>&1; then
        RATE_READY=1
        break
    fi
    if ! kill -0 "$RATE_PID" 2>/dev/null; then
        fail "低限流 fixture 服务启动即退出"
    fi
    sleep 0.5
done
[[ "$RATE_READY" -eq 1 ]] || fail "30 秒内低限流 /api/v1/health 未返回 200"

echo "[E2E] 执行 Gradle 契约测试：:app:e2eContract"
set +e
(cd "$ROOT" && ./gradlew --console=plain :app:e2eContract \
    -Pe2eBaseUrl="$BASE_URL" \
    -Pe2eFeedbackLimitBaseUrl="$RATE_BASE_URL")
STATUS=$?
set -e

if [[ "$STATUS" -ne 0 ]]; then
    fail "契约联调用例未通过（exit=$STATUS）"
fi

echo "[E2E] 通过：fixture @ ${BASE_URL}，目录条件请求 / 匿名登录 / push / changes / 幂等重放 / LWW 冲突 / 401 / 跨用户 404 / 配对码 / 异步导出与一次性链接 / 令牌注销 / 反馈限流 429 全部通过"
