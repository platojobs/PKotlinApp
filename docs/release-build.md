# Release 签名构建与产物真实性验证（v0.30.0）

> 本文件记录 **本机自签名** 的 release 构建、签名校验与产物真实性检查。
> 证书是本地测试证书，**不能用于 Google Play 上传**；本仓库也**没有上架、没有提交审核**。

- 版本：`versionCode = 39`、`versionName = 0.30.0`
- 构建环境：macOS（arm64）、JDK 21、Gradle 8.14.3、AGP 8.13.1、Build-Tools 37.0.0
- 签名：本机自签名（`scripts/make-upload-keystore.sh` 生成，PKCS12 / RSA 2048 / 3650 天）

---

## 1. keystore 生成与签名配置

### 1.1 生成脚本

`scripts/make-upload-keystore.sh` 用 `keytool -genkeypair` 生成 PKCS12 自签名 keystore，
并写出 `keystore.properties`：

| 输入 | 来源 | 说明 |
| --- | --- | --- |
| 别名 | `KEY_ALIAS` 或交互输入 | 非交互模式必填 |
| keystore 密码 | `KEYSTORE_PASSWORD` 或交互输入 | 至少 6 位；非交互模式必填 |
| key 密码 | `KEY_PASSWORD` 或交互输入 | PKCS12 要求与 keystore 密码一致 |
| 输出路径 | `KEYSTORE_PATH` / `KEYSTORE_PROPS_PATH` | 默认仓库根 `upload-keystore.jks`、`keystore.properties` |
| 证书主体 | `KEY_DNAME` | 默认 `CN=PKotlinApp Local Test, O=platojobs, C=CN` |

密码不会出现在命令行参数里：脚本通过 `-storepass:env` / `-keypass:env` 交给 keytool。
脚本本身不含任何硬编码密码，也不打印密码。

本机执行（密码为一次性随机值，只写入被忽略的 `keystore.properties`）：

```bash
KEY_ALIAS=pkotlinapp-local-test KEYSTORE_PASSWORD="$(openssl rand -hex 16)" \
  ./scripts/make-upload-keystore.sh --non-interactive
```

脚本输出（已脱敏）：

```
[keystore] 生成密钥：/…/PKotlinApp/upload-keystore.jks（alias=pkotlinapp-local-test，有效期 3650 天）
正在为以下对象生成 2048 位 RSA 密钥对和自签名证书 (SHA384withRSA)（有效期为 3,650 天）：
	CN=PKotlinApp Local Test, OU=Local Test, O=platojobs, L=Shanghai, ST=Shanghai, C=CN
[keystore] 已写入 /…/PKotlinApp/keystore.properties（权限 600，未打印密码）
[keystore] SHA256 指纹：0C:9B:1E:5C:A2:19:06:5A:7D:29:27:4D:14:45:92:A9:C8:5B:31:FB:21:43:FA:37:94:C0:32:E9:17:D9:15:20
[keystore] 完成。该证书为自签名，仅用于本机/CI 验证，不能用于 Google Play 上传。
```

### 1.2 不入库保障

`.gitignore` 已显式排除：

```
keystore.properties
upload-keystore.jks
*.jks
*.keystore
*.p12
```

脚本结束前用 `git check-ignore` 复核，两个文件均被忽略：

```
keystore.properties ignored
upload-keystore.jks ignored
```

`keystore.properties` 的内容（值已脱敏）：

```
storeFile=upload-keystore.jks
storePassword=<redacted>
keyAlias=pkotlinapp-local-test
keyPassword=<redacted>
```

### 1.3 Gradle 侧签名配置

`app/build.gradle.kts` 从 `keystore.properties` 读取 `storeFile / storePassword / keyAlias / keyPassword`，
只有四个字段齐全且密钥文件存在时才创建 `release` signingConfig。

**缺失时不再静默产出 unsigned 包**：`preReleaseBuild` 会直接失败。实测（临时移走
`keystore.properties` 后执行 `./gradlew assembleRelease`）：

```
FAILURE: Build failed with an exception.
Execution failed for task ':app:preReleaseBuild'.
> 未找到 keystore.properties，release 构建必须签名。 请先在仓库根目录运行
  ./scripts/make-upload-keystore.sh 生成本地自签名测试密钥（自签名证书仅用于本机/CI 验证，不能用于 Google Play 上传）。
```

退出码 `1`，且 `app/build/outputs/apk/release/` 不存在任何 APK。

CI 的 release job 用同样脚本在 runner 上现场生成一次性密钥
（`KEYSTORE_PASSWORD="$(openssl rand -hex 16)"`），密码不写入仓库、不上传 artifacts。

---

## 2. 产物路径与体积

| 产物 | 路径 | 体积（字节） | 约合 |
| --- | --- | --- | --- |
| release APK | `app/build/outputs/apk/release/app-release.apk` | 4,203,892 | 4.01 MiB |
| release AAB | `app/build/outputs/bundle/release/app-release.aab` | 6,531,839 | 6.23 MiB |
| R8 mapping | `app/build/outputs/mapping/release/mapping.txt` | 35,666,252 | 34.0 MiB |
| debug APK（对照） | `app/build/outputs/apk/debug/app-debug.apk` | 11,830,090 | 11.28 MiB |
| bundletool APKS（本机验证） | `/tmp/app-release.apks` | 12,690,853 | 12.1 MiB（含全部设备配置） |

APK 命名是 `app-release.apk` 而不是 `app-release-unsigned.apk`，说明签名配置已生效。

---

## 3. minifyEnabled / shrinkResources 实际效果

`release` 构建配置为 `isDebuggable = false`、`isMinifyEnabled = true`、`isShrinkResources = true`。
以下为同一版本（0.30.0 / 39）下 debug 与 release 的实测对比：

| 指标 | debug | release | 变化 |
| --- | --- | --- | --- |
| APK 体积 | 11,830,090 B | 4,203,892 B | −64.5% |
| dex 文件数 | 18 | 1 | 18 → 1 |
| dex 总字节 | 24,473,740 B | 5,985,760 B | **−75.5%** |
| 类定义数（`class_defs_size`） | 15,048 | 5,812 | **−61.4%** |
| 方法引用数（`method_ids_size`） | 135,283 | 39,565 | −70.8% |
| 资源条目数（`aapt2 dump resources` 的 `resource 0x…` 行） | 7,198 | 6,018 | **−16.4%** |

统计命令：

```bash
# dex 体积
python3 -c "
import zipfile, os
for p in ['app/build/outputs/apk/debug/app-debug.apk',
          'app/build/outputs/apk/release/app-release.apk']:
    z=zipfile.ZipFile(p)
    d=[i.file_size for i in z.infolist() if i.filename.endswith('.dex')]
    print(p, len(d), sum(d), os.path.getsize(p))
"
# 类 / 方法数（逐个 dex 求和）
#   dexdump -f classes*.dex | grep class_defs_size / method_ids_size
# 资源条目数
aapt2 dump resources app/build/outputs/apk/debug/app-debug.apk   | grep -c "resource 0x"
aapt2 dump resources app/build/outputs/apk/release/app-release.apk | grep -c "resource 0x"
```

---

## 4. 混淆生效

- `app/build/outputs/mapping/release/mapping.txt` 存在，首行为 `# compiler: R8`、`# compiler_version: 8.13.17`、`# min_api: 24`。
- 文件共 314,861 行；其中以 `com.platojobs.pkotlinapp` 开头的类名映射 **1,000** 行。
- 示例（原类名 → 混淆后）：

```
com.platojobs.pkotlinapp.PKotlinApplication$onCreate$1 -> n4.a:
com.platojobs.pkotlinapp.data.account.AccountExportFile -> o4.a:
com.platojobs.pkotlinapp.data.account.AccountService -> o4.h:
com.platojobs.pkotlinapp.data.account.AccountService$claim$1 -> o4.c:
```

- `com.platojobs.pkotlinapp.data.remote.**`、`data.local.**`、`domain.sync.**` 等按
  `proguard-rules.pro` 显式保留（Retrofit/Moshi/Room/WorkManager 反射需要），其余业务类均被重命名。

---

## 5. 无调试痕迹

以 release APK 的 dex 为准（debug 作对照，证明检查本身有效）：

| 检查项 | release | debug（对照） |
| --- | --- | --- |
| `StartupDiagnostics` 字符串 | **0** | 21 |
| `cold-start.log` 字符串 | **0** | 2 |
| `ColdStart`（诊断日志 TAG） | **0** | — |
| `BuildConfig` 类 | 不存在（`apkanalyzer dex packages` 无匹配） | 存在 |

命令：

```bash
unzip -o app/build/outputs/apk/release/app-release.apk "classes*.dex" -d /tmp/relx
cat /tmp/relx/classes*.dex | strings | grep -c StartupDiagnostics   # 0
cat /tmp/relx/classes*.dex | strings | grep -c cold-start.log      # 0
```

原因：`StartupDiagnostics` 在 `src/release` 源集是空实现，真实 StrictMode/日志文件实现只在
`src/debug`；R8 又把空实现内联后删除。`BuildConfig.DEBUG` 在 release 中恒为 `false` 并被常量折叠。

`android:debuggable`：

```bash
apkanalyzer manifest debuggable app/build/outputs/apk/release/app-release.apk
# false

aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep -E "^package:|application-debuggable"
# package: name='com.platojobs.pkotlinapp' versionCode='39' versionName='0.30.0' … compileSdkVersion='36'
# （没有 application-debuggable 行）
```

---

## 6. 签名校验

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

关键输出：

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
V2 Signer: certificate DN: CN=PKotlinApp Local Test, OU=Local Test, O=platojobs, L=Shanghai, ST=Shanghai, C=CN
V2 Signer: certificate SHA-256 digest: 0c9b1e5ca219065a7d29274d144592a9c85b31fb2143fa3794c032e917d91520
V2 Signer: certificate SHA-1 digest: 052dc247e17e714103901c22038a3549a9a4e007
V2 Signer: certificate MD5 digest: 4c6bf37cdcf241928138a981b5da7498
V2 Signer: key algorithm: RSA
V2 Signer: key size (bits): 2048
```

证书指纹（SHA-256）：

```
0C:9B:1E:5C:A2:19:06:5A:7D:29:27:4D:14:45:92:A9:C8:5B:31:FB:21:43:FA:37:94:C0:32:E9:17:D9:15:20
```

v1 为 false 是预期结果：`minSdk = 24`，AGP 默认只做 v2 签名。

> ⚠️ 该证书为**自签名测试证书**，指纹仅用于区分本地构建，**不能用于 Play 上传**。
> 正式上架需使用 Play App Signing 或团队持有的上传密钥。

---

## 7. AAB 与 APKS 检查

AAB 内部结构（`unzip -l app-release.aab`）：

```
BundleConfig.pb
base/dex/classes.dex        5,985,760
base/manifest/AndroidManifest.xml   17,761
base/resources.pb           1,660,478
```

`bundletool validate`：

```
App Bundle information
------------
Feature modules:
	Feature module: base
		File: dex/classes.dex
		File: res/anim-v21/fragment_fast_out_extra_slow_in.xml
		…
```

无 validation error。用 `bundletool 1.18.2` 生成 APKS（以同一自签名证书签署）：

```bash
java -jar bundletool-all-1.18.2.jar build-apks \
  --bundle=app/build/outputs/bundle/release/app-release.aab \
  --output=/tmp/app-release.apks \
  --ks=upload-keystore.jks --ks-key-alias=pkotlinapp-local-test \
  --ks-pass=file:/tmp/kspass.txt --key-pass=file:/tmp/kspass.txt
```

结果：

| 检查项 | 结果 |
| --- | --- |
| `toc.pb` | 存在（9,210 B） |
| base master 模块 | `splits/base-master.apk` 存在（3,491,927 B） |
| 配置 split | 语言/密度 split 齐全（如 `splits/base-xxhdpi.apk` 53,595 B），APKS 共 12,690,853 B |
| base-master 签名 | `apksigner verify --verbose`：Verifies，v2 = true、v3 = true，证书指向同一自签名 |

> APKS 只是本地验证产物，未安装到真机/模拟器。

---

## 8. 仍未验证的部分

- **真机安装与运行**：release APK 未在真机安装；签名、R8、资源收缩后的运行时行为（尤其
  Retrofit/Moshi/Room/WorkManager 反射路径）仍需真机冒烟。
- **Play Console 上传**：未上传，未走内部测试轨道。
- **Play 审核**：未提交、未审核。当前签名证书为自签名，**不具备上传条件**。
- **正式上传密钥**：仓库不含；需要 Play App Signing 或团队正式密钥后才能生成可上传产物。
- **CI 实跑**：本机验证通过；`main` 与 tag 的 CI 结果见 GitHub Actions（本地无法代替）。
