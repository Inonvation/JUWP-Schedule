package edu.jxslu.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseKind

/**
 * 课程调色板：中饱和粉彩，实色块配白字。
 * 条数必须与 [edu.jxslu.schedule.domain.ScheduleCalculator.PALETTE_SIZE] 一致。
 * 12→16：理论 + 实验课的不同课名常超 12 个，12 桶取模回绕必然撞色。
 * 新色**追加在尾部**：下标 0–11 的含义不变，已入库课程的色值不受影响。
 */
val coursePalette: List<Color> = listOf(
    Color(0xFF6AAF8F),
    Color(0xFFE5A06A),
    Color(0xFFDE7B7B),
    Color(0xFF6F9ED6),
    Color(0xFFD080A0),
    Color(0xFF9480D0),
    Color(0xFFD4B050),
    Color(0xFF5FAEB8),
    Color(0xFF7A9EDE),
    Color(0xFF84B878),
    Color(0xFFF09070),
    Color(0xFF62B8B0),
    // —— 以下 4 色为扩容追加（12–15），与既有 12 色拉开色相/明度距离
    Color(0xFFB08968), // 棕褐
    Color(0xFF9FAF5F), // 橄榄黄绿
    Color(0xFFBE6FB0), // 洋红
    Color(0xFF90A4AE), // 蓝灰
)

fun courseColor(colorIndex: Int): Color =
    coursePalette[((colorIndex % coursePalette.size) + coursePalette.size) % coursePalette.size]

/**
 * 网格色块的样式参数（来自「显示设置」，由 WeekScreen 组装后传给各卡片）。
 * 默认值即 WakeUp 基准：6dp 圆角、全不透明、左对齐顶部起排、显示教师、显示虚线描边。
 *
 * [roomFontSp] / [teacherFontSp]：教室/教师行的**实际渲染 sp**，由 WeekScreen 按
 * 「用户目标 dp ÷ 网格密度倍率」解析后传入（网格整体还套着一层 fontScale，预除一次
 * 才能让净渲染值等于目标 dp）。null = 未单独设置，跟随课名倍率等比缩放（旧行为）。
 */
data class GridCellStyle(
    val cornerRadiusDp: Float = 6f,
    val opacity: Float = 1f,
    val centerHorizontal: Boolean = false,
    val centerVertical: Boolean = false,
    val showTeacher: Boolean = true,
    val showBorder: Boolean = true,
    val roomFontSp: Float? = null,
    val teacherFontSp: Float? = null,
    /** 地点前是否带「@」前缀。关掉可省一格宽度（7 列窄列里 @ 之后往往刚好挤掉一个字）。 */
    val showAtSign: Boolean = true,
)

/**
 * WakeUp 式课程色块虚线描边。
 * 必须挂在 `background` 之后（Modifier 顺序即绘制顺序，后画的在上层），
 * 内容（文字）仍在其上。内缩半个线宽，避免描边一半落在 clip 边界外被裁掉。
 * 圆角半径与色块本体一致，避免描边和底色错位。
 * [dark] 下收敛密度与透明度：白 0.85 的虚线在深底上会炸成一圈亮刺，降为 0.30 + 短虚线段。
 */
private fun Modifier.courseDashedBorder(
    cornerRadiusDp: Float,
    dark: Boolean,
): Modifier = drawBehind {
    val strokePx = 1.dp.toPx()
    val inset = strokePx / 2f
    val dashPx = if (dark) 3.dp.toPx() else 5.dp.toPx()
    val gapPx = if (dark) 3.dp.toPx() else 4.dp.toPx()
    // 根因：圆角半径内缩半线宽后，用户选 0 / 0.5dp 时会算出负值，负半径进 Skia 行为不可靠，夹到 0
    val radiusPx = (cornerRadiusDp.dp.toPx() - inset).coerceAtLeast(0f)
    drawRoundRect(
        color = Color.White.copy(alpha = if (dark) 0.30f else 0.85f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(radiusPx),
        style = Stroke(
            width = strokePx,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx)),
        ),
    )
}

private val parenRegex = Regex("[（(]([^）)]*)[）)]")
private val openParenTailRegex = Regex("[（(]([^）)]*)$")

/**
 * 地点压缩。
 *
 * 根因：教务返回的是 `教学南大楼(南B302)` 这种带楼栋全名的写法，
 * 7 列布局下单列内容宽约 37dp，原样显示会被截成 `教学南…`，反而看不到教室号。
 *
 * 规则（按顺序）：
 * 1. 取最后一对括号里的非空内容 → `教学南大楼(南B302)` → `南B302`
 * 2. 括号没闭合也认 → `教学北大楼(北B102` → `北B102`
 *    （老版本解析把结尾右括号 trim 掉了，库里已经存了一批这样的脏数据，只能在这层兜住）
 * 3. 只剩括号残留（教务对无地点课程返回 `()`）→ 视为无地点
 * 4. 没有括号就原样返回
 */
fun compactPosition(position: String): String {
    val raw = position.trim()
    if (raw.isEmpty()) return ""
    val inners = parenRegex.findAll(raw)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (inners.isNotEmpty()) return inners.last()

    val tail = openParenTailRegex.find(raw)?.groupValues?.get(1)?.trim()
    if (!tail.isNullOrEmpty()) return tail

    return if (raw.any { it == '(' || it == '（' || it == ')' || it == '）' }) "" else raw
}

/**
 * 周课网格里的课程色块。
 *
 * 视觉基准对齐 WakeUp：色块几乎填满格子（四周只留 1dp，由调用方的 inset 决定）、
 * 小圆角、白色虚线描边；内容**顶部起排**（课名 → @地点），教师沉到块底部——
 * 之前全部居中让多行课名的块上下留白不均，且教师位置随内容漂移，扫读时没有固定锚点。
 * 居中方式与教师显隐由 [GridCellStyle] 控制（显示设置里可调）。
 *
 * [days] 决定字号：7 天时单列内容宽约 40dp，中文一行 3 字，超出会被截断，因此课名压到 11sp；
 * 5 天模式单列约 70dp，可以放宽到 12.5sp（见 DESIGN 3.2）。
 */
@Composable
fun GridCourseCard(
    course: Course,
    days: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GridCellStyle = GridCellStyle(),
) {
    val compact = days > 5
    val nameSize = if (compact) 11.sp else 12.5.sp
    val metaSize = if (compact) 9.sp else 10.5.sp
    // 教室/教师：用户单独设置过就用解析好的 sp，否则回落到 meta 档（跟随课名倍率）
    val roomSize = style.roomFontSp?.sp ?: metaSize
    val teacherSize = style.teacherFontSp?.sp ?: metaSize
    val accent = courseColor(course.colorIndex)
    val location = compactPosition(course.position)
    val align = if (style.centerHorizontal) TextAlign.Center else TextAlign.Start
    val columnAlign = if (style.centerHorizontal) Alignment.CenterHorizontally else Alignment.Start
    // surface 明度区分深浅色：深色下描边收敛，避免白虚线扎眼
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
            .background(accent.copy(alpha = style.opacity))
            .then(
                if (style.showBorder) {
                    Modifier.courseDashedBorder(style.cornerRadiusDp, isDark)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = if (style.centerVertical) Arrangement.Center else Arrangement.Top,
            horizontalAlignment = columnAlign,
        ) {
            Text(
                text = course.name,
                style = TextStyle(
                    fontSize = nameSize,
                    lineHeight = nameSize * 1.2f,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color.White,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
            )
            if (location.isNotBlank()) {
                Text(
                    text = if (style.showAtSign) "@$location" else location,
                    style = TextStyle(fontSize = roomSize, lineHeight = roomSize * 1.15f),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = align,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (style.showTeacher && course.teacher.isNotBlank()) {
                // 竖直居中时教师跟在地点后面；否则沉到块底，位置有固定锚点
                if (!style.centerVertical) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = course.teacher,
                    style = TextStyle(fontSize = teacherSize, lineHeight = teacherSize * 1.15f),
                    color = Color.White.copy(alpha = 0.74f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 实验角标占据右下角，教师名预先让位，避免长教师名压到角标底下
                    modifier = Modifier.padding(
                        end = if (course.kind == CourseKind.Lab) 20.dp else 0.dp,
                    ),
                )
            }
        }
        // 实验课标一个右下角小字。用标注而不是换色：颜色已被「不同课程不同色」占用，
        // 再拿颜色区分类型会和既有语义打架。
        if (course.kind == CourseKind.Lab) {
            Text(
                text = "实验",
                style = TextStyle(fontSize = 8.5.sp, lineHeight = 10.sp),
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 0.dp),
            )
        }
    }
}

/** 非本周课程灰态：只出现在空闲格子里，用来回答「这个时段本来有没有课」。 */
@Composable
fun GhostCourseCard(
    name: String,
    days: Int,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Float = 6f,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textSize = if (days > 5) 10.sp else 11.5.sp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(onSurface.copy(alpha = 0.035f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            style = TextStyle(fontSize = textSize, lineHeight = textSize * 1.2f),
            color = onSurface.copy(alpha = 0.30f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp),
        )
    }
}

/** 单小节课块：高度只够放课程名，居中显示。圆角/不透明度/水平对齐跟随 [GridCellStyle]。 */
@Composable
fun SingleSectionCard(
    course: Course,
    days: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GridCellStyle = GridCellStyle(),
) {
    val accent = courseColor(course.colorIndex)
    val textSize = if (days > 5) 10.sp else 11.5.sp
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(style.cornerRadiusDp.dp))
            .background(accent.copy(alpha = style.opacity))
            .then(
                if (style.showBorder) {
                    Modifier.courseDashedBorder(style.cornerRadiusDp, isDark)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = if (style.centerHorizontal) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = course.name,
            style = TextStyle(
                fontSize = textSize,
                lineHeight = textSize * 1.2f,
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (style.centerHorizontal) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        // 单节课块（约 40–53dp）放不下「实验」二字，退化成一个小圆点；
        // 完整类型在点开后的课程详情里给
        if (course.kind == CourseKind.Lab) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
fun EmptyHint(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun CourseEditorFields(
    name: String,
    onName: (String) -> Unit,
    teacher: String,
    onTeacher: (String) -> Unit,
    position: String,
    onPosition: (String) -> Unit,
    day: Int,
    onDay: (Int) -> Unit,
    startSection: Int,
    onStart: (Int) -> Unit,
    endSection: Int,
    onEnd: (Int) -> Unit,
    weeksText: String,
    onWeeks: (String) -> Unit,
    maxSection: Int = 11,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("课程名称") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = teacher,
        onValueChange = onTeacher,
        label = { Text("教师") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = position,
        onValueChange = onPosition,
        label = { Text("地点") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = day.toString(),
            onValueChange = { it.toIntOrNull()?.let(onDay) },
            label = { Text("星期") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = startSection.toString(),
            onValueChange = { it.toIntOrNull()?.let(onStart) },
            label = { Text("起始节") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(
            value = endSection.toString(),
            onValueChange = { it.toIntOrNull()?.let(onEnd) },
            label = { Text("结束节") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
    }
    Text(
        text = "节次按小节填 1–$maxSection（上午第 2 节就填 2，晚自习是 9–11）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = weeksText,
        onValueChange = onWeeks,
        label = { Text("周次，如 1-16 或 1,3,5") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { onWeeks("1-16") }) { Text("1-16周") }
        TextButton(onClick = { onWeeks(oddWeeks()) }) { Text("单周") }
        TextButton(onClick = { onWeeks(evenWeeks()) }) { Text("双周") }
    }
}

fun oddWeeks(): String = (1..16 step 2).joinToString(",")
fun evenWeeks(): String = (2..16 step 2).joinToString(",")

fun parseWeeksInput(text: String): Set<Int> {
    val result = mutableSetOf<Int>()
    for (part in text.split(',', '，')) {
        val p = part.trim()
        if (p.isEmpty()) continue
        val range = p.split('-', '—', '~')
        if (range.size == 2) {
            val a = range[0].trim().toIntOrNull()
            val b = range[1].trim().toIntOrNull()
            if (a != null && b != null && a in 1..40 && b in a..40) {
                result += (a..b)
            }
        } else {
            p.toIntOrNull()?.let { if (it in 1..40) result += it }
        }
    }
    return result
}

fun weeksToInput(weeks: Set<Int>): String = weeks.sorted().joinToString(",")
