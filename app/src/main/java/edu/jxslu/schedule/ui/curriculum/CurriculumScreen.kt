package edu.jxslu.schedule.ui.curriculum

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.CurriculumSync
import edu.jxslu.schedule.domain.CurriculumCourse
import edu.jxslu.schedule.domain.CurriculumRules
import edu.jxslu.schedule.domain.CurriculumYear
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.PageEmpty
import edu.jxslu.schedule.ui.common.PageRefreshAction
import edu.jxslu.schedule.ui.common.PageScaffold
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.showNotice
import kotlinx.coroutines.launch

/**
 * 培养方案（DESIGN §3.26 / §4.41）。
 *
 * 入口「我的 → 学习 → 培养方案」。数据在首启配置完成、冷启动过闸门时自动抓，
 * 打开本页通常已经有内容；页内提供「重新导入」强制刷新。
 *
 * 展示口径：按学年（大一~大四）分组、组内每学期一张**默认收起**的卡（点开看该学期
 * 课程明细）——教务给的就是「第几个学期建议上什么」，收起的卡片列表即一份目录，
 * 想通读整个方案也可以逐张点开。每门课一行，点开底部弹层看编号/各类学时等完整字段。
 * 「开设学期」是**计划口径**（建议修读学期），不等于实际开课/选课安排，脚注里写明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurriculumScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.curriculumRepository(context) }
    val sync = remember { Graph.curriculumSync(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = Room 流首帧还没到（未就绪），非 null 空列表 = 确实没有数据。
    // 与学业完成情况同一处收口：不区分的话首帧会闪一下「暂无数据」再跳真实内容。
    val coursesState by repo.courses.collectAsState(initial = null)
    val planName by prefs.curriculumPlanName.collectAsState(initial = "")
    val ready = coursesState != null
    val courses = coursesState.orEmpty()
    val years = remember(courses) { CurriculumRules.byYear(courses) }
    /** term=0 的「未标注」行（页面结构变化时的兜底）：不进学年分组，表尾单列一张卡。 */
    val unlabeled = remember(courses) {
        courses.filter { it.term !in 1..CurriculumRules.TERM_MAX }
    }

    var busy by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf(false) }
    /** 手动刷新失败的原因；空态时显示出来，免得用户只看到一句「暂无数据」。 */
    var lastError by remember { mutableStateOf<String?>(null) }
    /** 点了明细行、正在看完整字段的那门课（null = 没开弹层）。 */
    var detailCourse by remember { mutableStateOf<CurriculumCourse?>(null) }
    /**
     * 展开的学期号集合（空 = 全部收起；「未标注」区用 0 当键，与 1–8 不冲突）。
     * 默认收起：8 张卡头就是培养方案的目录，想读哪个学期点哪个——同学业完成情况
     * 的折叠心智一致，也免去 84 门一次性铺满的长滚动。
     */
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val listState = rememberLazyListState()

    fun runSync(force: Boolean, silent: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val result = sync.sync(force = force)
            busy = false
            when (result) {
                is CurriculumSync.Result.Updated -> {
                    lastError = null
                    snackbar.showNotice(
                        if (silent) "已获取 ${result.courses} 门课程"
                        else "已更新：${result.courses} 门课程",
                    )
                }
                // Skipped = 闸门没过（数据还新）；InProgress = 另一个触发点正在抓。
                // 两种都对用户无事可报
                CurriculumSync.Result.Skipped,
                CurriculumSync.Result.InProgress,
                -> Unit
                is CurriculumSync.Result.Failed -> {
                    lastError = result.reason
                    // 自动那次不弹提示（用户没主动要），但原因会留在空态里
                    if (!silent) snackbar.showNotice(result.reason)
                }
            }
        }
    }

    /** 展开时希望卡片下方能露出来的纵向空间（≈半屏）。见 [scrollToReveal]。 */
    val revealSpacePx = with(LocalDensity.current) { 240.dp.toPx() }

    /**
     * 展开长卡片时**只滚最小必要距离**，不把卡片顶到视口顶部（与学业完成情况同款）。
     *
     * 卡头在屏幕下半部时点开，明细大多落在屏幕外，用户会怀疑「点了没反应」；
     * 这里只补足下方缺的那点空间，上限是卡头贴到视口顶部。用 `animateScrollBy`
     * 滚固定距离——展开动画期间卡片还在变高，`animateScrollToItem` 会追着它抖。
     */
    fun scrollToReveal(key: String) {
        val layout = listState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val leftover = layout.viewportEndOffset - (info.offset + info.size)
        val need = revealSpacePx - leftover
        if (need <= 0f) return
        val maxScroll = (info.offset - layout.viewportStartOffset).coerceAtLeast(0).toFloat()
        val delta = minOf(need, maxScroll)
        if (delta > 0f) scope.launch { listState.animateScrollBy(delta) }
    }

    fun toggleTerm(termKey: Int, itemKey: String) {
        if (termKey in expanded) {
            expanded = expanded - termKey
        } else {
            expanded = expanded + termKey
            scrollToReveal(itemKey)
        }
    }

    // 进页自动尝试一次：闸门在 sync 内部判，数据还新时是零网络的空跑。
    // 只跑一次（autoTried），否则每次重组都会再打一次教务。
    LaunchedEffect(ready) {
        if (ready && !autoTried) {
            autoTried = true
            runSync(force = false, silent = true)
        }
    }

    PageScaffold(
        title = "培养方案",
        onBack = onBack,
        snackbar = snackbar,
        state = listState,
        actions = {
            PageRefreshAction(
                busy = busy,
                onClick = { runSync(force = true, silent = false) },
                label = "重新导入",
            )
        },
        // 首帧（Room 未发射）与首次抓取都算加载：原实现首帧画一屏纯空白
        loading = !ready || (busy && courses.isEmpty()),
        loadingText = if (!ready) "正在读取培养方案" else "正在从教务获取…",
        empty = if (!busy && courses.isEmpty()) {
            PageEmpty(
                title = "还没有培养方案数据",
                body = lastError
                    ?: "在首启引导里登录过教务的话，数据会自动获取；也可以现在手动导入一次。",
                actionLabel = "重新导入",
                onAction = { runSync(force = true, silent = false) },
            )
        } else null,
    ) {
        if (planName.isNotBlank()) {
            item {
                Text(
                    text = planName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        item { SummaryCard(courses) }

        years.forEach { year ->
            item(key = "year-${year.label}") { YearHeader(year) }
            items(year.terms, key = { "term-${it.term}" }) { term ->
                TermCard(
                    title = "第 ${term.term} 学期 · ${term.label}",
                    courses = term.courses,
                    expanded = term.term in expanded,
                    onToggle = { toggleTerm(term.term, "term-${term.term}") },
                    onCourseClick = { detailCourse = it },
                )
            }
        }

        if (unlabeled.isNotEmpty()) {
            item(key = "unlabeled") {
                TermCard(
                    title = "未标注学期",
                    courses = unlabeled,
                    // 兜底区用 0 当展开键；正常数据不出现，出现了说明页面结构在变
                    expanded = 0 in expanded,
                    onToggle = { toggleTerm(0, "unlabeled") },
                    onCourseClick = { detailCourse = it },
                )
            }
        }

        item {
            Text(
                text = "数据来自教务「培养方案」，开设学期为建议修读学期（计划口径），" +
                    "以教务处正式安排为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    // 明细行的完整字段（列表里放不下的那些：编号 / 体系 / 各类学时）
    detailCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = { detailCourse = null })
    }
}

@Composable
private fun SummaryCard(courses: List<CurriculumCourse>) {
    val termCount = courses.map { it.term }.filter { it in 1..CurriculumRules.TERM_MAX }.distinct().size
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem(label = "课程", value = "${courses.size} 门", emphasized = true)
            StatItem(label = "计划学分", value = trimNum(CurriculumRules.totalCredit(courses)), emphasized = false)
            StatItem(label = "覆盖学期", value = "$termCount 个", emphasized = false)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, emphasized: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/** 学年分组头：「大一 · 27 门 · 51 学分」。 */
@Composable
private fun YearHeader(year: CurriculumYear) {
    Text(
        text = "${year.label} · ${year.courseCount} 门 · ${trimNum(year.credits)} 学分",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * 一个学期的课程卡（也可作「未标注学期」兜底卡），**默认收起、点头行展开**。
 *
 * 与学业完成情况的分组卡同款交互：只有头行 + 副行可点（涟漪半径按按下时的卡片尺寸算，
 * 整卡可点会在展开瞬间画出一个盖不满的灰圆；也免得在明细里滚动误触收起）；
 * 展开 / 收起用 250 / 200ms 的 `AnimatedVisibility`（展开慢一档、收起快一档），
 * 箭头同步旋转——瞬变会让人怀疑自己点没点上。
 */
@Composable
private fun TermCard(
    title: String,
    courses: List<CurriculumCourse>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCourseClick: (CurriculumCourse) -> Unit,
) {
    val haptics = rememberAppHaptics()
    val credit = courses.sumOf { it.credit ?: 0.0 }
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = if (expanded) "收起 $title" else "展开 $title",
                    ) {
                        haptics.tap()
                        onToggle()
                    }
                    .padding(AppCardDefaults.Padding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${courses.size} 门 · ${trimNum(credit)} 学分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                val arrowAngle by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(if (expanded) 250 else 200),
                    label = "termArrow",
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).rotate(arrowAngle),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
                exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200)),
            ) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                ) {
                    courses.forEach { course ->
                        CourseRow(course = course, onClick = { onCourseClick(course) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseRow(course: CurriculumCourse, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点（不只点课程名）：列表里放不下编号 / 体系 / 各类学时，点开弹层看全；
            // 手指目标也大一些
            .clickable(onClickLabel = "查看 ${course.name} 的详细信息") {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = courseSubLine(course),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val badge = hoursBadge(course)
        if (badge.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

/** 明细行的副行：性质 · 属性 · 学分；空项跳过，不留一串分隔符。 */
private fun courseSubLine(course: CurriculumCourse): String {
    val parts = mutableListOf<String>()
    if (course.kind.isNotBlank()) parts += course.kind
    if (course.attr.isNotBlank()) parts += course.attr
    course.credit?.let { parts += "${trimNum(it)} 学分" }
    return if (parts.isEmpty()) course.tixi else parts.joinToString(" · ")
}

/** 行尾的学时摘要：优先总学时；纯实践课（总学时 0、实践「2(周)」）给实践栏。 */
private fun hoursBadge(course: CurriculumCourse): String {
    val total = course.hoursTotal
    if (total.isNotBlank() && total != "0") return "$total 学时"
    val practice = course.hoursPractice
    if (practice.isNotBlank() && practice != "0") return "实践 $practice"
    return ""
}

/**
 * 明细行的完整字段。
 *
 * 列表里一行只放得下「名称 + 性质·属性·学分 + 学时摘要」，剩下这些（编号、体系、
 * 五格学时）在核对培养方案、填表时会用到，藏在第二层比塞进副行更清楚。
 */
@Composable
private fun CourseDetailSheet(course: CurriculumCourse, onDismiss: () -> Unit) {
    ImeAwareModalBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 字段多（最多 11 行），小屏或大字号下要能滚
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (course.tixi.isNotBlank()) {
                Text(
                    text = course.tixi,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("课程编号", course.courseNo)
            DetailRow("开设学期", termText(course.term))
            DetailRow("课程性质", course.kind)
            DetailRow("课程属性", course.attr)
            DetailRow("学分", course.credit?.let { trimNum(it) + " 学分" }.orEmpty())
            DetailRow("理论学时", course.hoursTheory)
            DetailRow("实践学时", course.hoursPractice)
            DetailRow("讲座学时", course.hoursLecture)
            DetailRow("实验学时", course.hoursExperiment)
            DetailRow("总学时", course.hoursTotal)
        }
    }
}

/** 开设学期的展示：`第 3 学期 · 大二上`；0 = 未标注。 */
private fun termText(term: Int): String {
    if (term !in 1..CurriculumRules.TERM_MAX) return "未标注"
    return "第 $term 学期 · ${CurriculumRules.termLabel(term)}"
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

/** 数值展示：整数不带小数点，小数最多两位（161.0 → 161，23.75 → 23.75）。 */
private fun trimNum(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}
