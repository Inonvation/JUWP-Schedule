package edu.jxslu.schedule.ui.scholar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScholarProgressSync
import edu.jxslu.schedule.domain.ScholarCourse
import edu.jxslu.schedule.domain.ScholarCourseStatus
import edu.jxslu.schedule.domain.ScholarDimension
import edu.jxslu.schedule.domain.ScholarGroup
import edu.jxslu.schedule.domain.ScholarProgressRules
import edu.jxslu.schedule.domain.ScholarTotals
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppCardDefaults
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch

/**
 * 学业完成情况（DESIGN §3.17 / §4.29）。
 *
 * 入口「我的 → 学习 → 学业完成情况」。数据在首启配置完成、冷启动过闸门时自动抓，
 * 打开本页通常已经有内容；页内提供「重新导入」强制刷新。
 *
 * 展示口径三条：
 * 1. 四个维度切换看的是**同一批课程的不同切法**（教务四个 tab 就是这么给的），
 *    不是四份数据；
 * 2. 汇总卡由分组求和得来（口径见 [ScholarProgressRules.totals]），不用进度条百分比——
 *    教务那个数在「已修远超要求」时会给出 17200% 这种值，照抄只会让人怀疑眼睛；
 * 3. 刷新失败**必须说出来**：自动导入可以静默，用户手动点的那次不能装没事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Graph.scholarProgressRepository(context) }
    val sync = remember { Graph.scholarProgressSync(context) }
    val prefs = remember { Graph.displayPrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // null = Room 流首帧还没到（未就绪），非 null 空列表 = 确实没有数据。
    // 与成绩页同一处收口：不区分的话首帧会闪一下「暂无数据」再跳真实内容。
    val groupsState by repo.groups.collectAsState(initial = null)
    val coursesState by repo.courses.collectAsState(initial = null)
    val planName by prefs.scholarPlanName.collectAsState(initial = "")
    val ready = groupsState != null && coursesState != null
    val groups = groupsState.orEmpty()
    val courses = coursesState.orEmpty()

    var dimensionId by rememberSaveable { mutableStateOf(ScholarDimension.System.id) }
    var busy by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf(false) }
    /** 手动刷新失败的原因；空态时显示出来，免得用户只看到一句「暂无数据」。 */
    var lastError by remember { mutableStateOf<String?>(null) }
    /** 展开的分组名（每个维度各一份，用「维度|分组」当键，切维度不会串）。 */
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    /**
     * 展开后**连全部课程一起铺开**的分组键。
     *
     * 不在里面的分组展开时只给前 [GROUP_PREVIEW_COUNT] 门，剩下的折成一行「显示其余 N 门」。
     * 理由是实测数字：课程体系一个分组 34 门、课程属性「必修」一组 66 门，一次性铺开就是
     * 几千 dp，展开动画和视线都跟不上——用户点了卡片，内容却整片刷在屏幕外。
     */
    var fullyExpanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 抓取进度 `(正在抓第几个, 共几个)`；null = 没有在抓。四个页面串行要几秒，值得报一下。 */
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    /** 点了明细行、正在看完整字段的那门课（null = 没开弹层）。 */
    var detailCourse by remember { mutableStateOf<ScholarCourse?>(null) }
    val listState = rememberLazyListState()

    val dimension = ScholarDimension.fromId(dimensionId) ?: ScholarDimension.System
    val dimGroups = remember(groups, dimensionId) { groups.filter { it.dimension == dimensionId } }
    val dimCourses = remember(courses, dimensionId) {
        courses.filter { it.dimension == dimensionId }.groupBy { it.groupName }
    }
    // 「在修」从明细现算（见 ScholarProgressRules.totals 的说明），所以要连课程一起传
    val totals = remember(dimGroups, dimCourses) {
        ScholarProgressRules.totals(dimGroups, dimCourses.values.flatten())
    }

    // 搜索：在当前维度内按课程名或编号过滤。命中的分组才留下，且全部强制展开
    // （收起状态下搜索等于什么都没搜到，用户还得再点一下）
    val searching = query.isNotBlank()
    val matchedCourses = remember(dimCourses, query) {
        ScholarProgressRules.filterForSearch(dimCourses, query)
    }
    val visibleGroups = remember(dimGroups, matchedCourses, searching) {
        // 非搜索要给出全部维度分组（含一门课都没有的「经济管理类」这种——那本身是信息：
        // 这个类别你还没修）。`dimCourses` 的键只有有课的分组，所以这里不能用它反推
        if (!searching) dimGroups else dimGroups.filter { matchedCourses.containsKey(it.name) }
    }
    /** 当前维度所有分组的展开键（「全部展开 / 收起」按钮按它整批增删）。 */
    val dimKeys = remember(dimGroups) {
        dimGroups.map { "${it.dimension}|${it.name}" }.toSet()
    }
    val allExpanded = dimKeys.isNotEmpty() && expanded.containsAll(dimKeys)

    fun runSync(force: Boolean, silent: Boolean) {
        if (busy) return
        busy = true
        progress = null
        scope.launch {
            val result = sync.sync(force = force) { done, total -> progress = done to total }
            busy = false
            progress = null
            when (result) {
                is ScholarProgressSync.Result.Updated -> {
                    lastError = null
                    snackbar.showSnackbar(
                        if (silent) "已获取 ${result.courses} 门课程的完成情况"
                        else "已更新：${result.courses} 门课程 / ${result.groups} 个分组",
                    )
                }
                // Skipped = 闸门没过（数据还新）；InProgress = 另一个触发点正在抓。
                // 两种都对用户无事可报
                ScholarProgressSync.Result.Skipped,
                ScholarProgressSync.Result.InProgress,
                -> Unit
                is ScholarProgressSync.Result.Failed -> {
                    lastError = result.reason
                    // 自动那次不弹提示（用户没主动要），但原因会留在空态里
                    if (!silent) snackbar.showSnackbar(result.reason)
                }
            }
        }
    }

    /** 展开后希望卡片下方能露出来的纵向空间（≈半屏）。见 [scrollToReveal]。 */
    val revealSpacePx = with(LocalDensity.current) { 240.dp.toPx() }

    /**
     * 展开长卡片时**只滚最小必要距离**，而不是把这张卡顶到视口顶部。
     *
     * 之前用 `animateScrollToItem` 顶到顶部，和 250ms 的展开动画叠在一起：卡片先「跳」上去、
     * 内容再从下方刷出来，点击的位置和内容出现的位置对不上，因果感就散了（用户反馈的
     * 「视觉展开反馈在屏幕下方」）。现在分两种情况：
     * - 卡片下方本来就够放（[revealSpacePx] 之内）→ **不滚**，就地展开，位置感不被打断；
     * - 不够 → 只往上滚刚好补足的那点距离，上限是卡片自身贴到视口顶部
     *   （再滚卡片就跑出屏幕，用户会不知道自己在看哪一组）。
     *
     * 用 `animateScrollBy` 而不是 `animateScrollToItem`：滚固定距离，不追着还在变高的卡片跑，
     * 展开动画期间不会抖。
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

    // 进页自动尝试一次：闸门在 sync 内部判，数据还新时是零网络的空跑。
    // 只跑一次（autoTried），否则每次重组都会再打一次教务。
    LaunchedEffect(ready) {
        if (ready && !autoTried) {
            autoTried = true
            runSync(force = false, silent = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学业完成情况", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            progress?.let { (done, total) ->
                                Text(
                                    text = "$done/$total",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 14.dp).width(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) query = ""
                        }) {
                            Icon(
                                imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = if (searchOpen) "关闭搜索" else "搜索课程",
                            )
                        }
                        IconButton(onClick = { runSync(force = true, silent = false) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新导入")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!ready) {
            // 首帧：什么都不画，比闪一下空态好
            Spacer(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        if (groups.isEmpty()) {
            EmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                busy = busy,
                progress = progress,
                error = lastError,
                onRetry = { runSync(force = true, silent = false) },
            )
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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

            item { DimensionChips(dimension = dimension, onPick = { dimensionId = it.id }) }

            if (searchOpen) {
                item { CourseSearchField(query = query, onQueryChange = { query = it }) }
            }

            item { TotalsCard(totals = totals) }

            item {
                GroupListHeader(
                    groupCount = visibleGroups.size,
                    courseCount = matchedCourses.values.sumOf { it.size },
                    // 搜索时全部强制展开了，这个按钮按下去没有可见效果，索性不给
                    allExpanded = allExpanded,
                    onToggleAll = if (searching) null else {
                        {
                            if (allExpanded) {
                                expanded = expanded - dimKeys
                                fullyExpanded = fullyExpanded - dimKeys
                            } else {
                                // 「全部展开」的意思就是要通读，不再留「显示其余」的折行
                                expanded = expanded + dimKeys
                                fullyExpanded = fullyExpanded + dimKeys
                            }
                        }
                    },
                )
            }

            if (searching && visibleGroups.isEmpty()) {
                item {
                    Text(
                        text = "当前维度下没有匹配「$query」的课程",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }

            items(visibleGroups, key = { it.dimension + "|" + it.name }) { group ->
                val key = "${group.dimension}|${group.name}"
                GroupCard(
                    group = group,
                    courses = matchedCourses[group.name].orEmpty(),
                    // 搜索时强制展开：收起状态下命中了也看不见，等于白搜
                    expanded = searching || key in expanded,
                    // 搜索命中的本来就不多，不再折半；手动展开的走「前 N 门」
                    showAllCourses = searching || key in fullyExpanded,
                    onToggle = {
                        val willExpand = key !in expanded
                        if (willExpand) {
                            // 每次展开都从「前 N 门」重新开始：不带上次「显示其余」的状态，
                            // 否则再展开又是一下子几千 dp
                            fullyExpanded = fullyExpanded - key
                            expanded = expanded + key
                            scrollToReveal(key)
                        } else {
                            expanded = expanded - key
                        }
                    },
                    onShowAllCourses = { fullyExpanded = fullyExpanded + key },
                    onCourseClick = { detailCourse = it },
                )
            }

            item {
                Text(
                    text = "数据来自教务「学业达成情况」，仅供参考；以教务处正式单据为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    // 明细行的完整字段（列表里放不下的那些：编号 / 类别 / 性质 / 是否学位课 / 备注）
    detailCourse?.let { course ->
        CourseDetailSheet(course = course, onDismiss = { detailCourse = null })
    }
}

@Composable
private fun GroupListHeader(
    groupCount: Int,
    courseCount: Int,
    allExpanded: Boolean,
    onToggleAll: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$groupCount 个分组 · $courseCount 门课",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f),
        )
        // 「全部展开」是为几十门课准备的：课程体系维度一个分组就有几十条，
        // 想通读一遍要逐个点开太累。搜索时不给这个按钮（那时本来就全展开了）
        if (onToggleAll != null) {
            TextButton(onClick = onToggleAll) {
                Text(if (allExpanded) "全部收起" else "全部展开")
            }
        }
    }
}

/**
 * 明细行的完整字段。
 *
 * 列表里一行只放得下「名称 + 学期·属性·学分·成绩 + 状态」，剩下这些（编号、类别、性质、
 * 是否学位课、备注）在保研、填表、找老师核对时会用到，藏在第二层比塞进副行更清楚。
 */
@Composable
private fun CourseDetailSheet(course: ScholarCourse, onDismiss: () -> Unit) {
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
            if (course.groupName.isNotBlank()) {
                Text(
                    text = course.groupName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            DetailRow("课程编号", course.courseNo)
            DetailRow("修读学期", course.term)
            DetailRow("学分", trimNum(course.credit) + " 学分")
            DetailRow("课程类别", course.category)
            DetailRow("课程属性", course.attribute)
            DetailRow("课程性质", course.nature)
            DetailRow("修读情况", StatusLabel(course.status))
            DetailRow("课程成绩", course.scoreText)
            DetailRow("计划性质", course.planned?.let { if (it) "计划内" else "计划外" }.orEmpty())
            DetailRow(
                "是否学位课",
                course.degreeCourse?.let { if (it) "是" else "否" }.orEmpty(),
            )
            DetailRow("备注", course.remark)
        }
    }
}

/** key-value 行。空值直接不显示——教务有整列不输出的时候（比如公选课维度没有课程属性）。 */
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
private fun DimensionChips(dimension: ScholarDimension, onPick: (ScholarDimension) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScholarDimension.entries.forEach { item ->
            FilterChip(
                selected = item == dimension,
                onClick = { onPick(item) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun TotalsCard(totals: ScholarTotals) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TotalItem("要求学分", totals.required, emphasized = false)
            TotalItem("已修学分", totals.earned, emphasized = true)
            TotalItem("正修读", totals.ongoing, emphasized = false)
            TotalItem("还需学分", totals.remaining, emphasized = false)
        }
        if (totals.required > 0.0) {
            Spacer(Modifier.height(10.dp))
            val ratio = (totals.earned / totals.required).coerceIn(0.0, 1.0).toFloat()
            // 进度条平滑到目标值（首次加载、切维度时都看得出在动）；旁边的文字用目标值，
            // 免得数字跟着动画一路跳
            val animatedRatio by animateFloatAsState(
                targetValue = ratio,
                animationSpec = tween(600),
                label = "creditProgress",
            )
            LinearProgressIndicator(
                progress = { animatedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已修 ${trimNum(totals.earned)} / 要求 ${trimNum(totals.required)} 学分" +
                    "（${(ratio * 100).toInt()}%）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun TotalItem(label: String, value: Double, emphasized: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = trimNum(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * 一个分组。
 *
 * 头行固定，明细折叠：课程体系维度一个分组就有几十门课，全展开要滑很久；
 * 而用户第一眼要的是「哪块没修完」，所以头行给结论与进度，明细点开才看。
 */
@Composable
private fun GroupCard(
    group: ScholarGroup,
    courses: List<ScholarCourse>,
    expanded: Boolean,
    showAllCourses: Boolean,
    onToggle: () -> Unit,
    onShowAllCourses: () -> Unit,
    onCourseClick: (ScholarCourse) -> Unit,
) {
    val haptics = rememberAppHaptics()
    // contentPadding = 0：点击区要铺满整张卡的宽度（含左右内边距），所以内边距移到
    // 点击区的里面，不然涟漪覆盖不到两侧那 14dp 的留白
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // **只有「头行 + 副行」可点**，展开出来的明细不参与。两个原因：
                //
                // 1. 涟漪半径在**按下那一刻**按当时的卡片尺寸算。整卡可点的话，收起时按
                //    70dp 高算出一个小圆，卡片紧接着展开成几千 dp，那个圆就停在原地盖不满，
                //    看起来就是一团突兀的灰色（用户报的就是这个）；
                // 2. 展开后卡片很长，在明细里上下滚动很容易误触「收起」。
                //
                // 锁在头行之后，点击区高度固定，涟漪铺得匀，也不会误触。
                .clickable(
                    onClickLabel = if (expanded) "收起 ${group.name}" else "展开 ${group.name}",
                ) {
                    haptics.tap()
                    onToggle()
                }
                .padding(AppCardDefaults.Padding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                group.passed?.let { passed -> ResultTag(passed) }
                // 箭头跟着展开态转。用动画而不是 `rotate(if (…) 180f else 0f)`：瞬变会让人
                // 怀疑自己点没点上，尤其是明细还要 250ms 才铺开的那一下。
                // 时长与下面 AnimatedVisibility 配对，展开慢一档、收起快一档（与笔记库同口径）
                val arrowAngle by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    animationSpec = tween(if (expanded) 250 else 200),
                    label = "groupArrow",
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).rotate(arrowAngle),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = groupSummaryLine(group, courses.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
            exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200)),
        ) {
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            ) {
                // 先给前 N 门。实测一个分组最多 66 门，一次铺开几千 dp——展开动画在跑，
                // 内容却整片落在屏幕外，用户看到的只有卡片自己长了一下
                val preview = if (showAllCourses) courses else courses.take(GROUP_PREVIEW_COUNT)
                preview.forEach { course ->
                    CourseRow(course = course, onClick = { onCourseClick(course) })
                }
                val rest = courses.drop(preview.size)
                AnimatedVisibility(
                    visible = rest.isNotEmpty(),
                    enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(250)),
                    exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(200)),
                ) {
                    ShowRestRow(courseCount = rest.size, onClick = onShowAllCourses)
                }
            }
        }
    }
}

/** 「显示其余 N 门」——长分组的折行。点开才铺剩下的，且同样带动画（不然又变成瞬变）。 */
@Composable
private fun ShowRestRow(courseCount: Int, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "显示其余 $courseCount 门课程") {
                haptics.tap()
                onClick()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "显示其余 $courseCount 门",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 头行的第二行：「已修 57.5 / 要求 58.5 · 还需 0.8 · 12 门」。缺哪项就不显示哪项。 */
private fun groupSummaryLine(group: ScholarGroup, courseCount: Int): String {
    val parts = mutableListOf<String>()
    val earned = group.earnedCredit
    val required = group.requiredCredit
    if (earned != null || required != null) {
        parts += "已修 ${trimNum(earned ?: 0.0)}" + if (required != null && required > 0.0) {
            " / 要求 ${trimNum(required)}"
        } else {
            ""
        }
    }
    group.ongoingCredit?.takeIf { it > 0.0 }?.let { parts += "在修 ${trimNum(it)}" }
    group.remainingCredit?.takeIf { it > 0.0 }?.let { parts += "还需 ${trimNum(it)}" }
    // 门数放在最后：它是「多少东西」的规模感，收起状态下最想知道的就是它
    if (courseCount > 0) parts += "$courseCount 门"
    return if (parts.isEmpty()) "教务未提供该分组的学分口径" else parts.joinToString(" · ")
}

@Composable
private fun ResultTag(passed: Boolean) {
    val bg = if (passed) PASS_BG else FAIL_BG
    val fg = if (passed) PASS_FG else FAIL_FG
    Text(
        text = if (passed) "已达成" else "未达成",
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CourseRow(course: ScholarCourse, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点（不只点课程名）：列表里放不下编号 / 类别 / 性质 / 是否学位课 / 备注，
            // 点开弹层看全；手指目标也大一些
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
        Spacer(Modifier.width(8.dp))
        Text(
            text = StatusLabel(course.status),
            style = MaterialTheme.typography.labelSmall,
            color = statusColor(course.status),
        )
    }
}

/** 明细行的副行：学期 · 属性 · 成绩；空项跳过，不留一串分隔符。 */
private fun courseSubLine(course: ScholarCourse): String {
    val parts = mutableListOf<String>()
    if (course.term.isNotBlank()) parts += course.term
    if (course.attribute.isNotBlank()) parts += course.attribute
    if (course.credit > 0.0) parts += "${trimNum(course.credit)} 学分"
    if (course.scoreText.isNotBlank() && course.scoreText != "0") parts += "成绩 ${course.scoreText}"
    if (course.planned == false) parts += "计划外"
    return if (parts.isEmpty()) course.nature else parts.joinToString(" · ")
}

@Composable
private fun statusColor(status: String): androidx.compose.ui.graphics.Color =
    when (status) {
        ScholarCourseStatus.Earned.label -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        ScholarCourseStatus.Ongoing.label -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    }

/** 状态标签：库里存的是 label，未知值原样显示（脏数据也不至于变成空白）。 */
private fun StatusLabel(status: String): String =
    ScholarCourseStatus.entries.firstOrNull { it.label == status }?.label ?: status

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    busy: Boolean,
    progress: Pair<Int, Int>?,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = when {
                    !busy -> "还没有学业完成情况数据"
                    progress != null -> "正在从教务获取…（${progress.first}/${progress.second}）"
                    else -> "正在从教务获取…"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = error
                    ?: "在首启引导里登录过教务的话，数据会自动获取；也可以现在手动导入一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (!busy) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRetry) { Text("重新导入") }
            }
        }
    }
}

/**
 * 搜索框。打开搜索就自动聚焦——用户点搜索图标的意思就是要打字，不该再点一次输入框。
 */
@Composable
private fun CourseSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("搜索课程名或课程编号") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清空")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/** 学分数值展示：整数不带小数点，小数最多两位（79.0 → 79，24.25 → 24.25）。 */
private fun trimNum(value: Double): String {
    val rounded = Math.round(value * 100) / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

private val PASS_BG = androidx.compose.ui.graphics.Color(0x1A34A853)
private val PASS_FG = androidx.compose.ui.graphics.Color(0xFF1E8E3E)
private val FAIL_BG = androidx.compose.ui.graphics.Color(0x1AD93025)
private val FAIL_FG = androidx.compose.ui.graphics.Color(0xFFC5221F)

/**
 * 展开分组时先铺几门。
 *
 * 8 门 ≈ 半屏多一点：够看清这一组是什么内容，又不至于让卡片一次长到屏幕外。
 * 实测最长的一组 66 门（课程属性「必修」），全铺开是几千 dp。
 */
private const val GROUP_PREVIEW_COUNT = 8
