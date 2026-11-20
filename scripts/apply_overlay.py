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

    # 6) SettingsScreen：扩展能力区块的 5 个回调参数
    patch(
        settings,
        "    onFreeTokenClick: () -> Unit = {},\n) {",
        "    onFreeTokenClick: () -> Unit = {},\n"
        "    // 扩展能力（热力图 / Key 池 / 群聊 / 插件 / 派发）\n"
        "    onHeatmapClick: () -> Unit = {},\n"
        "    onKeyPoolClick: () -> Unit = {},\n"
        "    onGroupChatClick: () -> Unit = {},\n"
        "    onPluginClick: () -> Unit = {},\n"
        "    onDispatchClick: () -> Unit = {},\n) {",
        "扩展能力回调参数",
        "onHeatmapClick: () -> Unit = {}",
    )

    # 7) SettingsScreen：扩展能力区块（插在 Appearance 之前）
    patch(
        settings,
        "            // -- Appearance --\n",
        '            // -- 扩展能力 --\n'
        '            SettingsSection(title = "扩展能力") {\n'
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.BarChart,\n"
        "                    iconColor = Color(0xFF30A14E),\n"
        '                    title = "Token 使用热力图",\n'
        '                    subtitle = "近 26 周用量分布与按模型明细",\n'
        "                    onClick = onHeatmapClick,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.Shield,\n"
        "                    iconColor = Color(0xFF5856D6),\n"
        '                    title = "API Key 池",\n'
        '                    subtitle = "多 Key 加权轮询与差异化冷却",\n'
        "                    onClick = onKeyPoolClick,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.Groups,\n"
        "                    iconColor = Color(0xFFFF9F0A),\n"
        '                    title = "群聊编排",\n'
        '                    subtitle = "多成员调度与消息引用定向回复",\n'
        "                    onClick = onGroupChatClick,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.Extension,\n"
        "                    iconColor = Color(0xFF007AFF),\n"
        '                    title = "插件",\n'
        '                    subtitle = "清单校验、包路径防护与可撤销插槽",\n'
        "                    onClick = onPluginClick,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.Dashboard,\n"
        "                    iconColor = Color(0xFFFF453A),\n"
        '                    title = "多 Agent 派发",\n'
        '                    subtitle = "授权令牌校验与波次去重",\n'
        "                    onClick = onDispatchClick,\n"
        "                    showDivider = false,\n"
        "                )\n"
        "            }\n"
        "\n"
        "            // -- Appearance --\n",
        "扩展能力区块",
        'SettingsSection(title = "扩展能力")',
    )

    # 8) AppNavigation：5 个路由常量
    patch(
        nav,
        '    const val FREE_TOKEN = "free_token"',
        '    const val FREE_TOKEN = "free_token"\n'
        '    const val HEATMAP = "token_heatmap"\n'
        '    const val KEY_POOL = "api_key_pool"\n'
        '    const val GROUP_CHAT = "group_chat"\n'
        '    const val PLUGINS = "plugins"\n'
        '    const val DISPATCH = "agent_dispatch"',
        "扩展能力路由常量",
        'const val HEATMAP = "token_heatmap"',
    )

    # 9) AppNavigation：把 5 个回调接到导航
    patch(
        nav,
        "                onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) },\n",
        "                onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) },\n"
        "                onHeatmapClick = { navController.safeNavigate(Routes.HEATMAP) },\n"
        "                onKeyPoolClick = { navController.safeNavigate(Routes.KEY_POOL) },\n"
        "                onGroupChatClick = { navController.safeNavigate(Routes.GROUP_CHAT) },\n"
        "                onPluginClick = { navController.safeNavigate(Routes.PLUGINS) },\n"
        "                onDispatchClick = { navController.safeNavigate(Routes.DISPATCH) },\n",
        "扩展能力导航回调",
        "onHeatmapClick = { navController.safeNavigate(Routes.HEATMAP) }",
    )

    # 10) AppNavigation：注册 5 个目标页面
    patch(
        nav,
        "        composable(Routes.BACKUP) { entry ->",
        "        composable(Routes.HEATMAP) {\n"
        "            com.openminis.app.ui.opencode.TokenHeatmapScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.KEY_POOL) {\n"
        "            com.openminis.app.ui.opencode.ApiKeyPoolScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.GROUP_CHAT) {\n"
        "            com.openminis.app.ui.opencode.GroupChatScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.PLUGINS) {\n"
        "            com.openminis.app.ui.opencode.PluginScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.DISPATCH) {\n"
        "            com.openminis.app.ui.opencode.DispatchScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.BACKUP) { entry ->",
        "扩展能力页面注册",
        "composable(Routes.HEATMAP)",
    )

    # 11) build.gradle.kts：禁止 AGP 对 libopencode.so 做 strip。
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

    # 12) 人设页：路由常量
    patch(
        nav,
        '    const val DISPATCH = "agent_dispatch"',
        '    const val DISPATCH = "agent_dispatch"\n'
        '    const val PERSONAS = "personas"',
        "人设路由常量",
        'const val PERSONAS = "personas"',
    )

    # 13) 人设页：设置项回调参数
    patch(
        settings,
        "    onDispatchClick: () -> Unit = {},\n) {",
        "    onDispatchClick: () -> Unit = {},\n"
        "    // 人设管理（群成员的来源）\n"
        "    onPersonaClick: () -> Unit = {},\n) {",
        "人设回调参数",
        "onPersonaClick: () -> Unit = {}",
    )

    # 14) 人设页：设置入口行（紧跟多 Agent 派发）
    patch(
        settings,
        "                    onClick = onDispatchClick,\n"
        "                    showDivider = false,\n"
        "                )\n",
        "                    onClick = onDispatchClick,\n"
        "                    showDivider = true,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.BarChart,\n"
        "                    iconColor = Color(0xFFAF52DE),\n"
        '                    title = "人设",\n'
        '                    subtitle = "创建成员人格，供群聊绑定",\n'
        "                    onClick = onPersonaClick,\n"
        "                    showDivider = false,\n"
        "                )\n",
        "人设设置入口",
        'title = "人设"',
    )

    # 15) 人设页：导航回调
    patch(
        nav,
        "                onDispatchClick = { navController.safeNavigate(Routes.DISPATCH) },\n",
        "                onDispatchClick = { navController.safeNavigate(Routes.DISPATCH) },\n"
        "                onPersonaClick = { navController.safeNavigate(Routes.PERSONAS) },\n",
        "人设导航回调",
        "onPersonaClick = { navController.safeNavigate(Routes.PERSONAS) }",
    )

    # 16) 人设页：注册目标页面
    patch(
        nav,
        "        composable(Routes.BACKUP) { entry ->",
        "        composable(Routes.PERSONAS) {\n"
        "            com.openminis.app.ui.opencode.PersonaScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.BACKUP) { entry ->",
        "人设页面注册",
        "composable(Routes.PERSONAS)",
    )

    print("注入完成。")

if __name__ == "__main__":
    main()
