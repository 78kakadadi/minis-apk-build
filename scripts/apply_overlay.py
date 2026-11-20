#!/usr/bin/env python3
"""
把「免费 Token 通道」设置入口注入上游 Minis 源码。

为什么不直接覆盖整个文件：SettingsScreen.kt / AppNavigation.kt 各有几百到几千行，
整文件覆盖会在上游更新时静默丢失改动。这里只做定点插入，锚点找不到就报错退出，
宁可构建失败，也不产出「看起来成功但功能没接上」的包。
"""
import sys
import pathlib

ROOT = pathlib.Path("src/android/app/src/main/java/com/openminis/app")
GRADLE = pathlib.Path("src/android/app/build.gradle.kts")


def patch(path: pathlib.Path, old: str, new: str, label: str, marker: str) -> None:
    text = path.read_text(encoding="utf-8")

    if marker in text:
        print(f"  [skip] {label}：已注入")
        return

    count = text.count(old)
    if count != 1:
        print(f"  [FAIL] {label}：锚点出现 {count} 次（期望 1 次）", file=sys.stderr)
        sys.exit(1)

    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"  [ok]   {label}")


def main() -> None:
    settings = ROOT / "ui/settings/SettingsScreen.kt"
    nav = ROOT / "ui/navigation/AppNavigation.kt"

    for f in (settings, nav):
        if not f.exists():
            print(f"  [FAIL] 找不到 {f}", file=sys.stderr)
            sys.exit(1)

    print("注入设置入口：")

    # 1) SettingsScreen：新增回调参数（带默认值，不破坏其它调用方）
    patch(
        settings,
        "    onAboutClick: () -> Unit = {},\n) {",
        "    onAboutClick: () -> Unit = {},\n"
        "    // 免费 Token 通道（内嵌 OpenCode）\n"
        "    onFreeTokenClick: () -> Unit = {},\n) {",
        "SettingsScreen 参数",
        "onFreeTokenClick: () -> Unit = {}",
    )

    # 2) SettingsScreen：在 LLM Providers 段末尾加入口行
    patch(
        settings,
        "                    onClick = onUsageClick,\n"
        "                    showDivider = false,\n"
        "                )\n"
        "            }",
        "                    onClick = onUsageClick,\n"
        "                    showDivider = true,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.BarChart,\n"
        "                    iconColor = Color(0xFF32D74B),\n"
        '                    title = "免费 Token 通道",\n'
        '                    subtitle = "内嵌 OpenCode，无需 API Key",\n'
        "                    onClick = onFreeTokenClick,\n"
        "                    showDivider = false,\n"
        "                )\n"
        "            }",
        "SettingsScreen 入口行",
        'title = "免费 Token 通道"',
    )

    # 3) AppNavigation：路由常量
    patch(
        nav,
        '    const val SETTINGS = "settings"',
        '    const val SETTINGS = "settings"\n    const val FREE_TOKEN = "free_token"',
        "路由常量",
        'const val FREE_TOKEN = "free_token"',
    )

    # 4) AppNavigation：把回调接到导航
    patch(
        nav,
        "                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },\n"
        "            )",
        "                onSharedFoldersClick = { navController.safeNavigate(Routes.SHARED_FOLDERS) },\n"
        "                onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) },\n"
        "            )",
        "设置页导航回调",
        "onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) }",
    )

    # 5) AppNavigation：注册目标页面
    patch(
        nav,
        "        composable(Routes.BACKUP) { entry ->",
        "        composable(Routes.FREE_TOKEN) {\n"
        "            com.openminis.app.ui.settings.FreeTokenSettingsScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.BACKUP) { entry ->",
        "目标页面注册",
        "composable(Routes.FREE_TOKEN)",
    )

    # 6) build.gradle.kts：禁止 AGP 对 libopencode.so 做 strip。
    #    它是 199 MB 的 Bun 单文件二进制，strip 既慢又可能破坏运行时元数据；
    #    而它本来就没有可剥离的调试符号需要归档。
    patch(
        GRADLE,
        "    compileSdk = 36\n",        "    compileSdk = 36\n"
        "\n"
        "    // libopencode.so 是自包含的 Bun + OpenCode 单文件二进制，\n"
        "    // 不能被 NDK strip：既没有可剥离符号，strip 还可能破坏运行时元数据。\n"
        "    packaging {\n"
        "        jniLibs {\n"
        '            keepDebugSymbols += "**/libopencode.so"\n'
        "        }\n"
        "    }\n",
        "build.gradle.kts 禁止 strip",
        'keepDebugSymbols += "**/libopencode.so"',
    )

    print("注入完成。")

if __name__ == "__main__":
    main()
