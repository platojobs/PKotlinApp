#!/usr/bin/env bash
# 生成本地自签名 keystore + keystore.properties，供 release 构建使用。
#
# ⚠️ 这是**本地/CI 测试用**自签名证书，不能用于 Google Play 上传。
#    keystore、keystore.properties 与密码都只留在本机，均由 .gitignore 排除。
#
# 非交互（CI）用法：
#   KEY_ALIAS=ci-selfsigned KEYSTORE_PASSWORD=xxx ./scripts/make-upload-keystore.sh --non-interactive
#
# 交互用法：
#   ./scripts/make-upload-keystore.sh
#
# 可选环境变量：
#   KEY_ALIAS             密钥别名（默认交互询问，回车为 upload）
#   KEYSTORE_PASSWORD     keystore 密码（默认交互读取）
#   KEY_PASSWORD          密钥密码（PKCS12 要求与 keystore 密码一致；默认沿用）
#   KEYSTORE_PATH         keystore 输出路径（默认仓库根 upload-keystore.jks）
#   KEYSTORE_PROPS_PATH   keystore.properties 输出路径（默认仓库根）
#   KEY_DNAME             证书主体（默认 CN=PKotlinApp Local Test, O=platojobs, C=CN）
#   KEY_VALIDITY_DAYS     有效期天数（默认 3650）
#   FORCE                 已存在时是否覆盖（1 = 覆盖，默认不覆盖）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_PATH="${KEYSTORE_PATH:-$ROOT/upload-keystore.jks}"
PROPS_PATH="${KEYSTORE_PROPS_PATH:-$ROOT/keystore.properties}"
ALIAS="${KEY_ALIAS:-}"
STORE_PASS="${KEYSTORE_PASSWORD:-}"
KEY_PASS="${KEY_PASSWORD:-}"
VALIDITY_DAYS="${KEY_VALIDITY_DAYS:-3650}"
DNAME="${KEY_DNAME:-CN=PKotlinApp Local Test, OU=Local Test, O=platojobs, L=Shanghai, ST=Shanghai, C=CN}"

NON_INTERACTIVE=0
if [[ "${1:-}" == "--non-interactive" ]]; then
    NON_INTERACTIVE=1
fi

fail() {
    echo "[keystore] 错误：$*" >&2
    exit 1
}

command -v keytool >/dev/null 2>&1 || fail "未找到 keytool，请安装 JDK 21 并把 keytool 加入 PATH"
[[ ! -e "$KEYSTORE_PATH" || "${FORCE:-0}" == "1" ]] || fail \
    "$KEYSTORE_PATH 已存在；如确认重建请设置 FORCE=1（旧密钥将被覆盖）"

if [[ -z "$ALIAS" ]]; then
    (( NON_INTERACTIVE == 0 )) || fail "非交互模式必须通过 KEY_ALIAS 指定密钥别名"
    read -r -p "密钥别名 [upload]: " ALIAS
    ALIAS="${ALIAS:-upload}"
fi

if [[ -z "$STORE_PASS" ]]; then
    (( NON_INTERACTIVE == 0 )) || fail "非交互模式必须通过 KEYSTORE_PASSWORD 指定密码"
    read -r -s -p "keystore 密码（至少 6 位，仅写入本地 keystore.properties）: " STORE_PASS
    echo
fi
[[ ${#STORE_PASS} -ge 6 ]] || fail "密码至少 6 位"

if [[ -z "$KEY_PASS" ]]; then
    if (( NON_INTERACTIVE == 1 )); then
        KEY_PASS="$STORE_PASS"
    else
        read -r -s -p "密钥密码（回车沿用 keystore 密码）: " KEY_PASS
        echo
        KEY_PASS="${KEY_PASS:-$STORE_PASS}"
    fi
fi
# PKCS12 不支持 store/key 使用不同密码，提前给出可读错误而不是让 keytool 半途失败。
[[ "$KEY_PASS" == "$STORE_PASS" ]] || fail "PKCS12 keystore 要求密钥密码与 keystore 密码一致"

mkdir -p "$(dirname "$KEYSTORE_PATH")" "$(dirname "$PROPS_PATH")"

# 密码通过环境变量传给 keytool（-storepass:env），避免出现在进程参数列表里。
export PKAPP_STORE_PASS="$STORE_PASS"
export PKAPP_KEY_PASS="$KEY_PASS"

echo "[keystore] 生成密钥：${KEYSTORE_PATH}（alias=${ALIAS}，有效期 ${VALIDITY_DAYS} 天）"
keytool -genkeypair \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -dname "$DNAME" \
    -storepass:env PKAPP_STORE_PASS \
    -keypass:env PKAPP_KEY_PASS \
    -noprompt

# storeFile 按 Gradle 的 rootProject.file(...) 解析：在仓库内写相对路径，仓库外写绝对路径。
if [[ "$KEYSTORE_PATH" == "$ROOT"/* ]]; then
    STORE_FILE_ENTRY="${KEYSTORE_PATH#"$ROOT"/}"
else
    STORE_FILE_ENTRY="$KEYSTORE_PATH"
fi

umask 077
cat > "$PROPS_PATH" <<EOF
# 本机签名配置（由 scripts/make-upload-keystore.sh 生成）。
# 禁止提交：该文件含密码，.gitignore 已排除。
storeFile=$STORE_FILE_ENTRY
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$PROPS_PATH"
echo "[keystore] 已写入 ${PROPS_PATH}（权限 600，未打印密码）"

if command -v git >/dev/null 2>&1 && git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
    check_ignored() {
        local path="$1"
        local rel="$path"
        if [[ "$path" == "$ROOT"/* ]]; then
            rel="${path#"$ROOT"/}"
        fi
        git -C "$ROOT" check-ignore -q -- "$rel"
    }
    for path in "$KEYSTORE_PATH" "$PROPS_PATH"; do
        check_ignored "$path" ||
            echo "[keystore] 警告：${path} 未被 .gitignore 覆盖，请检查 .gitignore" >&2
    done
fi

FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE_PATH" -alias "$ALIAS" \
    -storepass:env PKAPP_STORE_PASS 2>/dev/null | grep -E "^[[:space:]]*SHA256:" | head -n 1 | sed 's/.*SHA256: //')"
echo "[keystore] SHA256 指纹：${FINGERPRINT:-（未读取到）}"
echo "[keystore] 完成。该证书为自签名，仅用于本机/CI 验证，不能用于 Google Play 上传。"
echo "[keystore] 下一步：./gradlew assembleRelease bundleRelease"
