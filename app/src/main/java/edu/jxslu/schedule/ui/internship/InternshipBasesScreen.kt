package edu.jxslu.schedule.ui.internship

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.repo.InternshipBasesLoader
import edu.jxslu.schedule.domain.InternshipBase
import edu.jxslu.schedule.domain.InternshipBaseRules
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton

/**
 * 实习基地查看（DESIGN §3.27 / §4.43）。
 *
 * 入口「我的 → 学习 → 教务服务 → 实习基地查看」。数据 = 教务「实习基地查看」的
 * layui 接口直取（OkHttp，不经 WebView），**不落库**：低频参考数据，进页现取一次；
 * 失败 / 未登录在页内给出重试与去向指引（文案来自同步层的 `describe`）。
 *
 * 展示口径：按类别（校内 / 校外 / 其他）分小节，行 = 基地名称 + 单位·地址，
 * 行尾容纳人数；整行点开底部弹层看完整字段（负责人 / 电话 / 建成时间 / 简介等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InternshipBasesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val loader = remember { Graph.internshipBasesLoader(context) }

    var state by remember { mutableStateOf<LoadState>(LoadState.Loading) }
    /** 每次自增 = 一轮新加载（进页 / 重试 / 顶栏刷新）。 */
    var generation by remember { mutableStateOf(0) }
    /** 本轮是否强刷（顶栏刷新 / 错误重试 = true；进页 = false，走 12 小时快照缓存）。 */
    var forceRefresh by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<InternshipBase?>(null) }

    // 单飞：加载中顶栏刷新换成进度圈（点不着），重试按钮只在错误态出现
    LaunchedEffect(generation) {
        state = LoadState.Loading
        state = when (val result = loader.load(force = forceRefresh)) {
            is InternshipBasesLoader.Result.Loaded ->
                LoadState.Ready(result.bases, result.updateFailed)
            is InternshipBasesLoader.Result.Failed -> LoadState.Error(result.reason)
            // 单飞护栏下到不了这里；真撞上（别的入口在跑）给出路而不是空白
            InternshipBasesLoader.Result.InProgress -> LoadState.Error("正在获取中，稍后再试")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实习基地", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    JwWebActionButton("实习基地", JwUrls.INTERNSHIP_BASES_SHELL)
                    if (state is LoadState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 14.dp).width(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = {
                            forceRefresh = true
                            generation++
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            is LoadState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is LoadState.Error -> ErrorState(
                reason = s.reason,
                modifier = Modifier.fillMaxSize().padding(padding),
                onRetry = {
                    forceRefresh = true
                    generation++
                },
            )

            is LoadState.Ready -> if (s.bases.isEmpty()) {
                EmptyState(Modifier.fillMaxSize().padding(padding))
            } else {
                BaseList(
                    bases = s.bases,
                    updateFailed = s.updateFailed,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onClick = { detail = it },
                )
            }
        }
    }

    detail?.let { base ->
        BaseDetailSheet(base = base, onDismiss = { detail = null })
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Ready(
        val bases: List<InternshipBase>,
        val updateFailed: Boolean,
    ) : LoadState
    data class Error(val reason: String) : LoadState
}

@Composable
private fun BaseList(
    bases: List<InternshipBase>,
    updateFailed: Boolean,
    modifier: Modifier = Modifier,
    onClick: (InternshipBase) -> Unit,
) {
    val groups = remember(bases) { InternshipBaseRules.byCategory(bases) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "共 ${bases.size} 个基地 · 数据来自教务「实习基地查看」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (updateFailed) {
            // 更新失败回退旧快照（宁旧勿空）：如实说清展示的是上次数据
            item {
                Text(
                    text = "更新失败，当前显示上次获取的数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        groups.forEach { (category, list) ->
            item { CategoryHeader(category, list.size) }
            items(list) { base ->
                BaseCard(base = base, onClick = { onClick(base) })
            }
        }
        item {
            Text(
                text = "基地信息以教务处最新发布为准；需要办理实习手续时请在教务系统内进行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 类别小节头：「校外基地 · 12 个」。 */
@Composable
private fun CategoryHeader(title: String, count: Int) {
    Text(
        text = "$title · $count 个",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BaseCard(base: InternshipBase, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 整行可点：列表里放不下地址/联系人/电话等完整字段，点开弹层看全
                .clickable(onClickLabel = "查看 ${base.name} 的详细信息") {
                    haptics.tap()
                    onClick()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = base.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = baseSubLine(base)
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (base.capacity.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "容纳 ${base.capacity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** 行的副行：所属单位 · 地址；空项跳过，不留一串分隔符。 */
private fun baseSubLine(base: InternshipBase): String {
    val parts = mutableListOf<String>()
    if (base.unit.isNotBlank()) parts += base.unit
    if (base.address.isNotBlank()) parts += base.address
    return parts.joinToString(" · ")
}

/** 完整字段弹层：列表一行放不下的都在这里（负责人 / 电话 / 时间 / 简介等）。 */
@Composable
private fun BaseDetailSheet(base: InternshipBase, onDismiss: () -> Unit) {
    ImeAwareModalBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 字段多，小屏或大字号下要能滚
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = base.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (base.category.isNotBlank()) {
                Text(
                    text = base.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("基地编号", base.no)
            DetailRow("所属单位", base.unit)
            DetailRow("所属院系", base.college)
            DetailRow("基地负责人", base.leader)
            DetailRow("基地电话", base.phone)
            DetailRow("基地地址", base.address)
            DetailRow("建成时间", base.builtDate)
            DetailRow("挂牌时限", base.listedYears)
            DetailRow("容纳人数", base.capacity)
            DetailRow("校内联系人", base.campusContact)
            DetailRow("校内电话", base.campusPhone)
            DetailRow("基地简介", base.intro)
        }
    }
}

/** key-value 行。空值直接不显示——教务有整格不输出的时候。 */
@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(text = "暂无实习基地数据", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "教务库里还没有可查看的基地，稍后再来看看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ErrorState(reason: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(text = "没取到实习基地数据", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
