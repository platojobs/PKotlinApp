# Release 签名构建

本文只记录签名、构建与产物校验的**可复现步骤**。历史产物体积、类数对比与具体指纹属于一次性测量，需要时按下面的命令重跑。

> 本地证书是自签名测试证书，**不能用于 Google Play 上传**；仓库未上架、未提交审核。

## 1. 生成 keystore

```bash
./scripts/make-upload-keystore.sh                      # 交互输入别名与密码
KEY_ALIAS=ci-selfsigned KEYSTORE_PASSWORD="$(openssl rand -hex 16)" \
  ./scripts/make-upload-keystore.sh --non-interactive  # CI / 无人值守
```

脚本用 `keytool -genkeypair` 生成 PKCS12（RSA 2048），密码通过 `-storepass:env` / `-keypass:env` 传递，不打印、不进命令行参数。输出路径可用 `KEYSTORE_PATH` / `KEYSTORE_PROPS_PATH` 覆盖，默认仓库根目录。

写入的 `keystore.properties`（已忽略）：

```properties
storeFile=upload-keystore.jks
storePassword=<密码>
keyAlias=<别名>
keyPassword=<同 keystore 密码>
```

`.gitignore` 已排除 `keystore.properties`、`upload-keystore.jks`、`*.jks`、`*.keystore`、`*.p12`；脚本结束用 `git check-ignore` 复核。

## 2. 构建

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./gradlew assembleRelease bundleRelease
```

`app/build.gradle.kts` 仅在 `storeFile / storePassword / keyAlias / keyPassword` 齐全且密钥文件存在时创建 `release` signingConfig；缺失时 `preReleaseBuild` 直接失败并提示运行脚本，不会静默产出 `app-release-unsigned.apk`。

release 配置保持：`isDebuggable = false`、`isMinifyEnabled = true`、`isShrinkResources = true`，规则见 `app/proguard-rules.pro`（Retrofit / Moshi / Room / WorkManager 反射类保留，其余业务类重命名）。

产物：

| 产物 | 路径 |
| --- | --- |
| APK | `app/build/outputs/apk/release/app-release.apk` |
| AAB | `app/build/outputs/bundle/release/app-release.aab` |
| mapping | `app/build/outputs/mapping/release/mapping.txt` |

## 3. 校验

```bash
APKSIGNER=$ANDROID_HOME/build-tools/36.0.0/apksigner
$APKSIGNER verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
apkanalyzer manifest debuggable app/build/outputs/apk/release/app-release.apk   # false
unzip -o app/build/outputs/apk/release/app-release.apk "classes*.dex" -d /tmp/relx
cat /tmp/relx/classes*.dex | strings | grep -c StartupDiagnostics                # 0
```

- `apksigner` 必须输出 `Verifies`；`minSdk = 24` 下 v2 签名为 `true`、v1 为 `false` 属预期。
- `mapping.txt` 首行为 `# compiler: R8`，含业务类映射。
- `StartupDiagnostics` 与 `cold-start.log` 在 release dex 中应为 0（诊断实现只在 `src/debug`）。
- `application-debuggable` 不得出现在 `aapt2 dump badging` 输出中。

AAB 可选验证：

```bash
bundletool validate --bundle=app/build/outputs/bundle/release/app-release.aab
```

## 4. 仍未验证

- release APK 未在真机安装；R8 / 资源收缩后的运行时行为（Retrofit、Moshi、Room、WorkManager 反射）需真机冒烟。
- 未上传 Play Console、未走内部测试、未提交审核。
- 仓库不含正式上传密钥，需要 Play App Signing 或团队密钥才能生成可上传产物。
- CI release job 在 runner 上现场生成一次性密钥并校验签名、mapping 与 debuggable，证书同样只用于验证链路。
