# 常见问题排查

命令默认在仓库根目录执行。

## JDK 必须是 21

报错形如 `Unsupported class file major version 69` / `requires Java 21 ... using Java 25`。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew assembleDebug
# 未安装：brew install --cask temurin@21
```

本项目不改全局 shell 配置，只在命令级指定。

## SDK 路径缺失

报错 `SDK location not found`，或缺 `platforms;android-36` / `build-tools;36.0.0`。

```properties
# local.properties（已忽略，不提交）
sdk.dir=/Users/<user>/Library/Android/sdk
```

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

## 产品 / 市场 / 同步加载失败

先确认同级仓库存在：`ls ../PJGoIoT/api/openapi.yaml`。默认地址 `http://10.0.2.2:8080/`（模拟器 → 宿主）。

```bash
./scripts/e2e-contract.sh               # 起 fixture 后端并跑契约联调
curl -i http://127.0.0.1:8080/api/v1/health
adb reverse tcp:8080 tcp:8080           # 真机可用反向代理
```

端口被占用时脚本自动回退 `18080`；手动起服务用 `PORT=18080 STORE=fixture go run ./cmd/server`，客户端加 `-PapiBaseUrl=http://10.0.2.2:18080/`。

## 更换 BASE_URL

优先级：`-PapiBaseUrl=...` > `local.properties` 的 `apiBaseUrl=...` > 默认 `http://10.0.2.2:8080/`。

```bash
./gradlew assembleDebug -PapiBaseUrl=https://your-host/
grep API_BASE_URL app/build/generated/source/buildConfig/debug/com/platojobs/pkotlinapp/BuildConfig.java
```

Debug 允许明文 HTTP；Release 必须 `https://`。地址以 `/` 结尾，换地址后如仍是旧内容，先 `adb shell pm clear com.platojobs.pkotlinapp`。

## Release 签名缺失 / 密码错误

签名信息只从仓库根目录的 `keystore.properties` 读取；缺字段或密钥文件不存在时 `preReleaseBuild` 直接失败（不会静默产出未签名包）。

```bash
./scripts/make-upload-keystore.sh        # 推荐：生成 keystore 并写入 properties
# 或手动放仓库外：
mkdir -p ../.pkotlinapp-secrets
keytool -genkeypair -v -keystore ../.pkotlinapp-secrets/pkotlinapp-release.jks \
  -alias pkotlinapp -keyalg RSA -keysize 2048 -validity 9125
```

```properties
storeFile=../.pkotlinapp-secrets/pkotlinapp-release.jks
storePassword=<密码>
keyAlias=pkotlinapp
keyPassword=<密码>
```

密钥库、`keystore.properties`、密码一律不入库。换签名后先 `adb uninstall com.platojobs.pkotlinapp` 再安装。

## 常用日志

```bash
adb logcat | grep -iE "OkHttp|ConnectException|UnknownHostException|HTTP"
```

Debug 的 OkHttp 日志级别为 `BASIC`，Release 关闭。
