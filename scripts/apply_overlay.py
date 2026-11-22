#!/usr/bin/env python3
"""
把「免费 Token 通道」设置入口注入上游 Minis 源码。

为什么不直接覆盖整个文件：SettingsScreen.kt / AppNavigation.kt 各有几百到几千行，
整文件覆盖会在上游更新时静默丢失改动。这里只做定点插入，锚点找不到就报错退出，
宁可构建失败，也不产出「看起来成功但功能没接上」的包。
"""
import sys
import pathlib

ROOT = pathlib.Path("src/android/app/src/main/java/pro/openminis/app")
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
    app_file = ROOT / "MinisApp.kt"
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
        '                    subtitle = "内嵌 OpenCode，可登录账号或免 Key 使用",\n'
        "                    onClick = onFreeTokenClick,\n"
        "                    showDivider = true,\n"
        "                )\n"
        "                SettingsItem(\n"
        "                    icon = Icons.Outlined.Person,\n"
        "                    iconColor = Color(0xFF4C8DFF),\n"
        '                    title = "OpenCode 账号登录",\n'
        '                    subtitle = "登录后用自己的额度，也可粘贴 API Key",\n'
        "                    onClick = onOpencodeAccountClick,\n"
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
        "            pro.openminis.app.ui.settings.FreeTokenSettingsScreen(\n"
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
        '                    subtitle = "一个地址多把 Key，额度用尽自动换",\n'
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
        "            pro.openminis.app.ui.opencode.TokenHeatmapScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.KEY_POOL) {\n"
        "            pro.openminis.app.ui.opencode.ApiKeyPoolScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.GROUP_CHAT) {\n"
        "            pro.openminis.app.ui.opencode.GroupChatScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.PLUGINS) {\n"
        "            pro.openminis.app.ui.opencode.PluginScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.DISPATCH) {\n"
        "            pro.openminis.app.ui.opencode.DispatchScreen(\n"
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
        "            pro.openminis.app.ui.opencode.PersonaScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.BACKUP) { entry ->",
        "人设页面注册",
        "composable(Routes.PERSONAS)",
    )

    # ======================================================================
    # 长按「新对话」按钮 → 群聊
    #
    # 上游这个长按手势原本弹的是「模型组」下拉（把新会话归到某个模型组），
    # 跟用户理解的「群聊」完全不是一回事。这里把它换成群聊入口：新建群 /
    # 进入已有群，并跳转到群聊页。
    # ======================================================================
    sessions = ROOT / "ui/sessions/SessionListScreen.kt"
    split = ROOT / "ui/navigation/ChatSplitScaffold.kt"
    onboarding = ROOT / "ui/onboarding/OnboardingScreen.kt"

    for f in (sessions, split, onboarding):
        if not f.exists():
            print(f"  [FAIL] 找不到 {f}", file=sys.stderr)
            sys.exit(1)

    # 17) 会话列表：新增回调参数
    patch(
        sessions,
        "    draftPlaceholderId: String? = null,\n) {",
        "    draftPlaceholderId: String? = null,\n"
        "    // 群聊入口（长按新对话按钮）：参数是群 id，null 表示只打开群列表\n"
        "    onGroupChatClick: (String?) -> Unit = {},\n) {",
        "会话列表群聊回调参数",
        "// 群聊入口（长按新对话按钮）",
    )

    # 18) 会话列表：把回调传给底部按钮行
    patch(
        sessions,
        "                    modelGroups = providerConfig.modelGroups,\n"
        "                    onSearchToggle = {",
        "                    modelGroups = providerConfig.modelGroups,\n"
        "                    onGroupChatClick = onGroupChatClick,\n"
        "                    onSearchToggle = {",
        "底部按钮行传参",
        "onGroupChatClick = onGroupChatClick,",
    )

    # 19) DualFabRow：新增参数
    patch(
        sessions,
        "    onNewChat: () -> Unit,\n"
        "    onNewChatWithGroup: (String) -> Unit,\n",
        "    onNewChat: () -> Unit,\n"
        "    onNewChatWithGroup: (String) -> Unit,\n"
        "    // 群聊入口：长按新对话按钮 → 群聊\n"
        "    onGroupChatClick: (String?) -> Unit,\n",
        "DualFabRow 群聊参数",
        "// 群聊入口：长按新对话按钮 → 群聊",
    )

    # 20) 长按新对话按钮 = 直接创建群聊并进入
    # DualFabRow 函数体里本来就有 `val context = LocalContext.current`，直接用它。
    patch(
        sessions,
        "                    .combinedClickable(\n"
        "                        onClick = onNewChat,\n"
        "                        onLongClick = {\n"
        "                            if (topGroups.isNotEmpty()) showGroupMenu = true\n"
        "                        },\n"
        "                    ),",
        "                    .combinedClickable(\n"
        "                        onClick = onNewChat,\n"
        "                        // 长按 = 直接创建群聊（默认名「新群聊」）并进入聊天界面，\n"
        "                        // 界面里的空成员引导会带用户去加成员。以前是弹一个\n"
        "                        // 底部弹层还要再点一次「创建」，用户感知是「长按没用」。\n"
        "                        onLongClick = {\n"
        "                            onGroupChatClick(\n"
        "                                pro.openminis.app.ext.group.GroupStore(context).createGroup(\"\").id,\n"
        "                            )\n"
        "                        },\n"
        "                    ),",
        "长按直接建群",
        "onLongClick = {\n"
        "                            onGroupChatClick(",
    )

    # 21) 下拉菜单 → 群聊弹层
    patch(
        sessions,
        "            DropdownMenu(\n"
        "                expanded = showGroupMenu,\n"
        "                onDismissRequest = { showGroupMenu = false },\n"
        "            ) {\n"
        "                topGroups.forEach { group ->\n"
        "                    DropdownMenuItem(\n"
        "                        text = { Text(group.name) },\n"
        "                        leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },\n"
        "                        onClick = {\n"
        "                            showGroupMenu = false\n"
        "                            onNewChatWithGroup(group.id)\n"
        "                        },\n"
        "                    )\n"
        "                }\n"
        "            }",
        "            pro.openminis.app.ui.opencode.GroupChatFabMenu(\n"
        "                expanded = showGroupMenu,\n"
        "                onDismiss = { showGroupMenu = false },\n"
        "                onOpen = { groupId ->\n"
        "                    showGroupMenu = false\n"
        "                    onGroupChatClick(groupId)\n"
        "                },\n"
        "            )",
        "长按弹层改为群聊",
        "pro.openminis.app.ui.opencode.GroupChatFabMenu(",
    )

    # 22) 在按钮行上方插入一次性提示条（Column 包裹）
    patch(
        sessions,
        "    Row(\n"
        "        modifier = modifier\n"
        "            .fillMaxWidth()\n"
        "            // T24: lift the FAB+search row above the IME so the text field\n"
        "            // remains visible while typing. Compose-managed inset — handles\n"
        "            // the IME open/close animation in lockstep.\n"
        "            .imePadding()\n"
        "            .padding(horizontal = 16.dp, vertical = 20.dp),",
        "    Column(modifier = modifier) {\n"
        "    // 首次进入时的「长按建群」提示，只出现一次\n"
        "    pro.openminis.app.ui.opencode.GroupChatFabHint()\n"
        "    Row(\n"
        "        modifier = Modifier\n"
        "            .fillMaxWidth()\n"
        "            // T24: lift the FAB+search row above the IME so the text field\n"
        "            // remains visible while typing. Compose-managed inset — handles\n"
        "            // the IME open/close animation in lockstep.\n"
        "            .imePadding()\n"
        "            .padding(horizontal = 16.dp, vertical = 20.dp),",
        "提示条插入",
        "pro.openminis.app.ui.opencode.GroupChatFabHint()",
    )

    # 23) 关闭上面新开的 Column
    patch(
        sessions,
        "        if (isSwapped) { chatFab() } else { searchFab() }\n"
        "    }\n"
        "}",
        "        if (isSwapped) { chatFab() } else { searchFab() }\n"
        "    }\n"
        "    // [/group-chat-fab-hint]\n"
        "    }\n"
        "}",
        "提示条 Column 收尾",
        "[/group-chat-fab-hint]",
    )

    # 24) 分屏脚手架：把群聊回调接到导航
    patch(
        split,
        "                onNetworkPageClick = { route -> navController.safeNavigate(route) },\n"
        "                selectedSessionId = selectedSessionId,",
        "                onNetworkPageClick = { route -> navController.safeNavigate(route) },\n"
        "                onGroupChatClick = { groupId ->\n"
        "                    if (groupId == null) navController.safeNavigate(Routes.GROUP_CHAT)\n"
        "                    else navController.safeNavigate(Routes.groupChatWith(groupId))\n"
        "                },\n"
        "                selectedSessionId = selectedSessionId,",
        "会话列表群聊导航",
        "Routes.groupChatWith(groupId)",
    )

    # 25) 路由：带群 id 的群聊
    patch(
        nav,
        '    const val PERSONAS = "personas"',
        '    const val PERSONAS = "personas"\n'
        "\n"
        "    /** 群聊直达：带群 id 打开（长按新对话按钮后进来）。 */\n"
        '    const val GROUP_CHAT_ONE = "group_chat/{groupId}"\n'
        "\n"
        '    fun groupChatWith(groupId: String): String = "group_chat/" + android.net.Uri.encode(groupId)',
        "群聊直达路由",
        'const val GROUP_CHAT_ONE = "group_chat/{groupId}"',
    )

    # 26) 注册带群 id 的群聊页面
    patch(
        nav,
        "        composable(Routes.GROUP_CHAT) {\n"
        "            pro.openminis.app.ui.opencode.GroupChatScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }",
        "        composable(Routes.GROUP_CHAT) {\n"
        "            pro.openminis.app.ui.opencode.GroupChatScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n"
        "\n"
        "        composable(\n"
        "            route = Routes.GROUP_CHAT_ONE,\n"
        "            arguments = listOf(navArgument(\"groupId\") { type = NavType.StringType }),\n"
        "        ) { entry ->\n"
        "            pro.openminis.app.ui.opencode.GroupChatScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "                initialGroupId = entry.arguments?.getString(\"groupId\"),\n"
        "            )\n"
        "        }",
        "群聊直达页面注册",
        "Routes.GROUP_CHAT_ONE",
    )

    # 27) 新手引导：模型选择之后加一页「长按建群」
    patch(
        onboarding,
        "        2 -> ModelSelectionStep(providerRepository = providerRepository, onComplete = onComplete)",
        "        2 -> ModelSelectionStep(providerRepository = providerRepository, onComplete = { step = 3 })\n"
        "        // 长按新对话按钮 → 群聊，作为引导的最后一页\n"
        "        3 -> pro.openminis.app.ui.opencode.OnboardingGroupChatStep(onComplete = onComplete)",
        "引导页新增群聊步骤",
        "OnboardingGroupChatStep(onComplete = onComplete)",
    )

    # 27b) 跳过 API Key 时也走到群聊引导，不要在最后一页前就结束
    patch(
        onboarding,
        "onNext = { step = 2 }, onSkip = onComplete)",
        "onNext = { step = 2 }, onSkip = { step = 3 })",
        "跳过 API Key 也看群聊引导",
        "onSkip = { step = 3 })",
    )

    # 28) 主页：群聊模块放在会话列表顶部（不依赖是否已有会话）
    patch(
        sessions,
        "            if (isInitialLoadComplete) Column(modifier = Modifier.fillMaxSize()) {\n",
        "            if (isInitialLoadComplete) Column(modifier = Modifier.fillMaxSize()) {\n"
        "                // 群聊模块：放在会话列表顶部，顺带在主页拉起免费通道\n"
        "                pro.openminis.app.ui.opencode.GroupChatHomeSection(\n"
        "                    onOpen = { gid -> onGroupChatClick(gid) },\n"
        "                    onCreate = { onGroupChatClick(null) },\n"
        "                )\n",
        "主页群聊模块",
        "GroupChatHomeSection(",
    )

    # 29) 只有群、没有会话时也要显示列表（否则群聊看不到）
    patch(
        sessions,
        "                if (sessions.isEmpty() && !(searchResultsActive && folderBlocks.isNotEmpty())) {",
        "                if (sessions.isEmpty() &&\n"
        "                    !pro.openminis.app.ui.opencode.hasGroupChats(context) &&\n"
        "                    !(searchResultsActive && folderBlocks.isNotEmpty())\n"
        "                ) {",
        "空状态让位给群聊",
        "hasGroupChats(context)",
    )

    # 30) 没有会话但有群时，底部新对话按钮仍要显示（否则无法长按建群）
    patch(
        sessions,
        "            } else if (hasProviders && (sessions.isNotEmpty() || isSearchActive)) {",
        "            } else if (hasProviders && (sessions.isNotEmpty() || isSearchActive ||\n"
        "                pro.openminis.app.ui.opencode.hasGroupChats(context))\n"
        "            ) {",
        "底部按钮常驻",
        "hasGroupChats(context))\n            ) {",
    )

    # 31) MinisApp：冷启动就拉起号池网关。
    #     号池注册成了普通 provider，用户可能在没进过设置页的情况下直接聊天，
    #     等 UI 打开再启动就晚了。
    patch(
        app_file,
        "        pro.openminis.app.service.OAuthWaitKeepAlive.install(this)\n",
        "        pro.openminis.app.service.OAuthWaitKeepAlive.install(this)\n"
        "        // API Key 号池：注册成普通 provider 后需要常驻本地网关\n"
        "        pro.openminis.app.ext.keypool.KeyPoolRegistry.init(this)\n",
        "号池冷启动",
        "ext.keypool.KeyPoolRegistry.init(this)",
    )

    # 32) OpenCode 账号登录：设置首页独立入口
    #     登录按钮原先只长在「免费 Token 通道」页里，从标题看不出跟账号有关，
    #     用户不会点进去找。这里给一行独立入口。
    patch(
        settings,
        "import androidx.compose.material.icons.outlined.BarChart\n",
        "import androidx.compose.material.icons.outlined.BarChart\n"
        "import androidx.compose.material.icons.outlined.Person\n",
        "账号入口图标",
        "icons.outlined.Person",
    )

    patch(
        settings,
        "    onFreeTokenClick: () -> Unit = {},\n",
        "    onFreeTokenClick: () -> Unit = {},\n"
        "    onOpencodeAccountClick: () -> Unit = {},\n",
        "账号入口参数",
        "onOpencodeAccountClick: () -> Unit",
    )

    patch(
        nav,
        '    const val FREE_TOKEN = "free_token"',
        '    const val FREE_TOKEN = "free_token"\n    const val OPENCODE_ACCOUNT = "opencode_account"',
        "账号页路由",
        'const val OPENCODE_ACCOUNT = "opencode_account"',
    )

    patch(
        nav,
        "                onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) },\n",
        "                onFreeTokenClick = { navController.safeNavigate(Routes.FREE_TOKEN) },\n"
        "                onOpencodeAccountClick = { navController.safeNavigate(Routes.OPENCODE_ACCOUNT) },\n",
        "账号页导航回调",
        "Routes.OPENCODE_ACCOUNT) },",
    )

    patch(
        nav,
        "        composable(Routes.FREE_TOKEN) {\n",
        "        composable(Routes.OPENCODE_ACCOUNT) {\n"
        "            pro.openminis.app.ui.settings.OpencodeAccountScreen(\n"
        "                onBack = { navController.safePopBackStack() },\n"
        "            )\n"
        "        }\n\n"
        "        composable(Routes.FREE_TOKEN) {\n",
        "账号页注册",
        "OpencodeAccountScreen(",
    )

    print("注入完成。")

if __name__ == "__main__":
    main()
