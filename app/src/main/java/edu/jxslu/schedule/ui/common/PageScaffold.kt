package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 二级页几何口径的唯一出处（2026-10-18 新增）。
 *
 * 为什么要有这个文件：审查发现 14 个二级页目录各自演化——顶栏标题 22sp/16sp 两套、
 * 卡片间距 8/10/12 三档、列表内边距两套、空态 5 套、首帧加载 5 种、提示宿主两套、
 * 弹窗动画 12 处错或缺。这些都不是"某个页面写错了"，而是**口径只活在文档和记忆里**，
 * 没人能保证下一个新页面照做。
 *
 * 所以把口径做成 API：新页面用 [PageScaffold] 就从结构上对了，不需要记住任何数值；
 * 要偏离只能显式传参，评审时一眼能看见。
 *
 * 与 `ui-common.md` 的分工：那份文件讲**为什么**（观感与已知的坑），本文件是它的
 * 可执行版本。改这里的数值等于改全 App 的二级页排版，改前先过一遍 DESIGN §3.2。
 */
object PageDefaults {
    /** 页面左右边距：全 App 二级页统一 16dp。 */
    val Horizontal = 16.dp

    /** 卡片列表的纵向间距：统一 10dp（原 8/12 两套并入）。 */
    val CardSpacing = 10.dp

    /**
     * 列表内容内边距。
     *
     * 顶 `vertical = 12.dp`：与 [edu.jxslu.schedule.ui.common.AppCardDefaults.Padding]
     * 的横向 14dp 组合后，首张卡的文字左缘落在 16 + 14 = 30dp，与今日页课程卡一致。
     */
    val ContentPadding = PaddingValues(horizontal = Horizontal, vertical = 12.dp)

    /**
     * 顶栏标题：16sp Medium。
     *
     * 全 App 二级页原为两套——`TopAppBar` 默认 `titleLarge`(22sp SemiBold) 与显式
     * `titleMedium`(16sp Medium) 各占一半。这里取后者：二级页标题是"当前位置"而非
     * 主窗口的"页面名"，16sp 与今日页/课表页的大标题（22sp）形成层级差，
     * 且长标题（「学业完成情况」「培养方案与课程设置」）在 360dp 屏上留得住右侧动作区。
     */
    val TitleStyle: TextStyle
        @Composable get() = MaterialTheme.typography.titleMedium
}

/**
 * 空态的声明式描述。
 *
 * 只给 [PageScaffold] 用：传 null = 不空（正常渲染 [PageScaffold] 的 content），
 * 传进来 = 渲染 [EmptyHint] 并跳过 content。**不要把空态写进 content**——
 * 那样 loading 与 empty 可能同时成立，又回到"先空态再跳数据"。
 */
data class PageEmpty(
    val title: String,
    val body: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** 二级页统一返回键：`AutoMirrored`（RTL 会翻转）+ 48dp 命中区（IconButton 默认）。 */
@Composable
fun PageBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

/**
 * 顶栏「刷新 / 重新导入」动作的统一形态（放在 [PageScaffold] 的 actions 槽里）。
 *
 * 原实现三种：图标置灰（选课中心）、换成 20dp 转圈（考试 / 学业 / 培养方案 / 实习基地）、
 * 什么都不变（天气 / 问渠是静默刷新）。这里统一前两种——[busy] 时换成转圈，
 * 让"点了有反应"成为默认行为；静默刷新那两页语义不同（保留旧内容），可继续自己写。
 */
@Composable
fun RowScope.PageRefreshAction(
    busy: Boolean,
    onClick: () -> Unit,
    label: String = "刷新",
) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 14.dp)
                .width(20.dp),
            strokeWidth = 2.dp,
        )
    } else {
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.Refresh, contentDescription = label)
        }
    }
}

/**
 * 列表型二级页骨架：顶栏 + 提示宿主 + [LazyColumn]（统一边距与间距）+ 加载/空态。
 *
 * 三件事由骨架保证，调用方不需要再写：
 * 1. **提示宿主**是 [AppSnackbarHost]（不是裸 `SnackbarHost`——那会让浅色主题下出现
 *    近黑浮条、且没有语气图标）；
 * 2. **加载 > 空态 > 内容**的判定顺序，且 `loading` 与 `empty` 都在视口内居中
 *    （原来"学业/培养方案"首帧是一屏纯空白、"实习基地"是裸转圈）；
 * 3. 顶栏标题带 `Ellipsis`，长标题不会把右侧动作区挤没。
 *
 * 用法：
 * ```
 * PageScaffold(
 *     title = "考试安排", onBack = onBack, snackbar = snackbar,
 *     actions = { PageRefreshAction(busy = busy, onClick = ::refresh) },
 *     loading = snapshot == null,
 *     loadingText = "正在读取考试安排",
 *     empty = if (rows.isEmpty()) PageEmpty("还没有考试安排", "…", "从教务更新", ::refresh) else null,
 * ) {
 *     items(rows) { ExamCard(it) }
 * }
 * ```
 *
 * [content] 只在 `loading == false && empty == null` 时执行，所以不用自己判空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    loading: Boolean = false,
    loadingText: String = "正在读取…",
    empty: PageEmpty? = null,
    /**
     * 列表内边距。默认 [PageDefaults.ContentPadding]（16/12）。
     * 迁移老页面时传它原来的值，保证「只换骨架、不动排版」——观感要改是另一件事。
     */
    contentPadding: PaddingValues = PageDefaults.ContentPadding,
    /** 卡片间距，默认 [PageDefaults.CardSpacing]（10dp）。同上：迁移时先传原值。 */
    spacing: Dp = PageDefaults.CardSpacing,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = PageDefaults.TitleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { PageBackButton(onBack) },
                actions = actions,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            when {
                loading -> item {
                    // 真空居中：fillParentMaxSize 取的是「视口 − contentPadding」，
                    // 所以加载态与空态都在用户能看见的那块区域正中，不是页面正中
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingHint(loadingText)
                    }
                }
                empty != null -> item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHint(
                            title = empty.title,
                            body = empty.body,
                            actionLabel = empty.actionLabel,
                            onAction = empty.onAction,
                        )
                    }
                }
                else -> content()
            }
        }
    }
}

/**
 * 表单 / 设置型二级页骨架：与 [PageScaffold] 同标题、同边距、同提示宿主，
 * 但内容是可滚动的 [Column] 而不是列表。
 *
 * 用于设置类页面（一行行 [SettingItem] 堆起来、不需要 lazy）。列表用 [PageScaffold]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffoldColumn(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = PageDefaults.TitleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { PageBackButton(onBack) },
                actions = actions,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PageDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(PageDefaults.CardSpacing),
            content = content,
        )
    }
}
