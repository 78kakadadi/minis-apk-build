# minis-apk-build

在 GitHub Actions 上把 [OpenMinis](https://github.com/openminis/openminis)（GPL-3.0）
编译成可安装的 arm64-v8a APK，并叠加多 Agent 扩展模块。

## 为什么不在本地编

本地沙箱没有 Android SDK / NDK，装齐要 1.5 GB 且无法验证真实编译。
GitHub Actions 的 `ubuntu-latest` 自带 Android 工具链生态，正好补上这一环。

## 为什么本仓库不含 Minis 源码

上游是 1497 个 Kotlin 文件 + 约 200 MB 原生依赖，不适合每次推送。
`build.yml` 每次现场 `git clone --recurse-submodules` 上游，再把本仓库的
`overlay/` 覆盖进去。好处是基线永远跟上游最新一致，且不用维护 fork 同步。

## 目录

```
.github/workflows/build.yml   # 完整构建流水线
overlay/                      # 增量模块，按 app 模块的源码根布局放置
  main/kotlin/...             # → src/android/app/src/main/kotlin/...
  main/java/...               # → src/android/app/src/main/java/...
```

`overlay/` 为空时构建的就是**原版 Minis**——这是第一步要验证的基线。

## 触发

推送到 `main` 自动触发，或在 Actions 页面手动 `Run workflow`（可选 debug / release）。

## 产物

Actions → 对应 run → Artifacts → `minis-apk-<run_number>`。

`assembleDebug` 与 `assembleRelease` 在上游配置里都用 debug 签名
（`signingConfig = signingConfigs.getByName("debug")`），因此都能直接安装覆盖。

## 构建链包含什么

| 步骤 | 产物 | 缺失后果 |
|---|---|---|
| `deps/build_proot.sh` | `assets/proot-aarch64`、`libproot*.so` | 沙箱起不来，所有命令返回 `[Shell not running]` |
| `scripts/prepare_android_sandbox.sh` | `assets/alpine-minirootfs.tar.gz` | 同上 |
| `deps/build_rclone_android.sh` | `app/libs/rclone.aar` | **编译直接失败**（app 模块的 implementation 依赖） |
| `sdkmanager ndk;28.x` | NDK | proot 交叉编译失败 |

## 许可

本仓库是 OpenMinis 的衍生构建配置，遵循 **GPL-3.0**。上游版权归其作者所有。
