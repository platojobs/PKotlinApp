# 常见问题排查

按「现象 → 原因 → 处理」组织。命令默认在仓库根目录执行。

---

## 1. JDK 版本不对

### 现象

```text
Unsupported class file major version 69
Android Gradle plugin requires Java 21 to run. You are currently using Java 25.
Could not determine java version from '25'
```

### 原因

本机默认 `JAVA_HOME` 可能指向 JDK 25，本项目固定使用 **JDK 21**（AGP 8.13.1 支持范围）。

### 处理

```bash
# 查看当前 Java
java -version

# 查看本机已安装的 JDK
/usr/libexec/java_home -V
```

所有构建命令临时指定 JDK 21：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew clean assembleDebug
```

或单条命令：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew testDebugUnitTest
```

注意：不要修改全局 shell 配置；本项目约定只在命令级指定。
若 JDK 21 未安装：

```bash
brew install --cask temurin@21
```

CI 已固定 `actions/setup-java` 的 `java-version: 21`，本地通过后 CI 一般不会因 JDK 失败。

---

## 2. SDK 路径缺失 / 平台没装

### 现象

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
variable or by setting the sdk.dir path in your project's local.properties file.
```

或构建时报缺少 `platforms;android-36`、`build-tools;36.0.0`。

### 处理

1. 确认 SDK 安装位置（Android Studio → Settings → Languages & Frameworks → Android SDK）。
2. 在仓库根目录创建/修正 `local.properties`：

```properties
sdk.dir=/Users/<user>/Library/Android/sdk
```

> `local.properties` 已被 `.gitignore` 忽略，不要提交。

3. 安装缺失组件：

```bash
export ANDROID_HOME=/Users/<user>/Library/Android/sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

4. 接受协议：

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

5. 验证：

```bash
adb version
ls $ANDROID_HOME/platforms | grep android-36
```

`apksigner`、`adb` 都在 `build-tools/36.0.0/` 与 `platform-tools/` 下。

---

## 3. Go 服务未启动（产品 / 市场 / 同步失败）

### 现象

- App 内产品 / 市场显示「加载失败：…」；
- 「我的」同步提示「同步失败：网络不可用…」；
- `adb logcat` 里出现 `ConnectException` / `UnknownHostException` / `failed to connect to /10.0.2.2:8080`。

### 原因

产品、市场、设备详情与同步都走 `BuildConfig.API_BASE_URL`，本地默认 `http://10.0.2.2:8080/`，需要后端真的在运行。

### 处理

1. 确认同级的 `PJGoIoT` 目录存在（脚本默认 `../PJGoIoT`）：

```bash
ls ../PJGoIoT/api/openapi.yaml
```

2. 一键启动并跑契约联调：

```bash
./scripts/e2e-contract.sh
```

脚本会：`go build` → `STORE=fixture` 启动 → 轮询 `/api/v1/health` → 执行 `:app:e2eContract` → 结束后清理。

3. 手动验证服务：

```bash
curl -i http://127.0.0.1:8080/api/v1/health
```

预期 `200`，响应中含 `store=fixture`。

4. 真机不想配局域网 IP 时，可用 adb 反向代理：

```bash
adb reverse tcp:8080 tcp:8080
./gradlew assembleDebug -PapiBaseUrl=http://127.0.0.1:8080/
```

> `adb reverse` 只在连接期间有效；拔线或重启 adb 后需要重新执行。

5. 若 `go build` 失败，先在本机确认：

```bash
cd ../PJGoIoT && go test ./... && go build ./...
```

把真实错误抄进验收记录，不要写成「已通过」。

---

## 4. 端口被占用

### 现象

```text
listen tcp :8080: bind: address already in use
```

`scripts/e2e-contract.sh` 会自动回退到 `18080` 并打印提示；手动启动时需要自己处理。

### 处理

```bash
# 查看谁占用 8080
lsof -nP -iTCP:8080 -sTCP:LISTEN

# 确认可结束后再终止（不要盲杀）
kill <PID>
```

如果 8080 被常驻服务占用，直接换端口：

```bash
cd ../PJGoIoT
PORT=18080 STORE=fixture go run ./cmd/server
```

对应跑测试：

```bash
./gradlew :app:e2eContract -Pe2eBaseUrl=http://127.0.0.1:18080
```

App 手动联调时也要同步改 BASE_URL：

```bash
./gradlew assembleDebug -PapiBaseUrl=http://10.0.2.2:18080/
```

---

## 5. 签名 keystore 缺失 / 密码错误

### 现象

```text
Keystore file '/path/...jks' not found for signing config 'release'.
Cannot recover key
storePassword is null
```

或 `assembleRelease` 成功但 `apksigner verify` 报未签名。

### 原因

`app/build.gradle.kts` 只有在仓库根目录存在 `keystore.properties` 时才创建 `release` signingConfig；缺失时产出**未签名** Release 包（用于编译验证）。

### 处理

1. 检查是否存在：

```bash
ls -l keystore.properties ../.pkotlinapp-secrets/pkotlinapp-release.jks
```

2. 没有则生成（密钥库放仓库外）：

```bash
mkdir -p ../.pkotlinapp-secrets
keytool -genkeypair -v \
  -keystore ../.pkotlinapp-secrets/pkotlinapp-release.jks \
  -alias pkotlinapp -keyalg RSA -keysize 2048 -validity 9125
```

3. 在仓库根目录创建 `keystore.properties`（**不要提交**）：

```properties
storeFile=../.pkotlinapp-secrets/pkotlinapp-release.jks
storePassword=<你的密码>
keyAlias=pkotlinapp
keyPassword=<你的密码>
```

4. 验证密钥库与别名：

```bash
keytool -list -v -keystore ../.pkotlinapp-secrets/pkotlinapp-release.jks
```

5. 重新构建并验证签名：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew assembleRelease
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

预期输出 `Verifies` 与证书指纹。

### 安全约定

- 密钥库、`keystore.properties`、密码一律不入库；CI 不注入签名密钥。
- 切换签名后必须卸载旧包再安装：`adb uninstall com.platojobs.pkotlinapp`。

---

## 6. Mock / 临时数据地址到期后如何更换 BASE_URL

### 背景

本项目**没有硬编码任何 Mocky 或临时 Mock 地址**。产品、市场、设备详情、同步全部使用同一个 `BuildConfig.API_BASE_URL`。
如果之前把地址写进了 Mock 服务（例如 Mocky 生成的临时 URL），该 URL 到期后页面会显示加载失败，需要换地址。

### 优先级（从高到低）

1. Gradle 属性 `-PapiBaseUrl=...`
2. 仓库根目录 `local.properties` 中的 `apiBaseUrl=...`
3. 默认 `http://10.0.2.2:8080/`

代码位置：[app/build.gradle.kts](../app/build.gradle.kts) 中 `configuredApiBaseUrl`。

### 更换步骤

方式 A（推荐，适合临时切换，不改文件）：

```bash
./gradlew assembleDebug -PapiBaseUrl=https://your-new-host/
```

方式 B（长期使用，写入本地配置）：

```properties
# local.properties
apiBaseUrl=https://your-new-host/
```

方式 C（本地 Go 服务）：

```properties
# local.properties
apiBaseUrl=http://10.0.2.2:8080/
```

然后重新构建安装：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
  ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 验证实际生效的地址

```bash
grep API_BASE_URL \
  app/build/generated/source/buildConfig/debug/com/platojobs/pkotlinapp/BuildConfig.java
```

或反编译检查（可选）：

```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex | strings | grep -E "https?://"
```

### 后端需要提供的接口

| 路径 | 用途 |
| --- | --- |
| `GET /api/v1/health` | 健康检查 |
| `POST /api/v1/auth/anonymous` | 匿名登录 |
| `GET /api/v1/sync/changes?cursor=` | 增量拉取 |
| `POST /api/v1/sync/push` | 批量推送 |
| `GET /api/products` | 型号库 |
| `GET /api/market` | 市场数据 |
| `GET /api/device/{mac}` | 设备详情 |

### 注意

- **Debug** 构建允许明文 HTTP；**Release** 不允许明文，BASE_URL 必须是 `https://`，否则请求直接失败。
- 地址必须以 `/` 结尾；构建脚本会自动补一个，但 `local.properties` 里写成 `https://host` 也可。
- 换地址后如果页面仍是旧内容，先确认 BuildConfig 已更新，再 `adb shell pm clear com.platojobs.pkotlinapp` 排除缓存。
- 排查网络请求可看日志：

```bash
adb logcat | grep -iE "OkHttp|ConnectException|UnknownHostException|HTTP"
```

Debug 构建的 OkHttp 日志级别为 `BASIC`，Release 关闭。
