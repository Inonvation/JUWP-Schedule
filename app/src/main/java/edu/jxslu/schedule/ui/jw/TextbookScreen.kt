package edu.jxslu.schedule.ui.jw

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.domain.Textbook
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.PageEmpty
import edu.jxslu.schedule.ui.common.PageScaffold
import edu.jxslu.schedule.ui.jwvw.JwWebActionButton
import kotlinx.coroutines.flow.collect

/**
 * 教务 → 教材（2026-10-20）。
 *
 * 数据已经在 Room 的 `textbooks` 表里，按当前课表学期整批展示；不再用网页占位。
 * 顶栏外链图标打开教务「学生教材确认」壳页，走统一的 [JwImportActivity] 浏览窗口。
 */
@Composable
fun TextbookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { Graph.repository(context) }
    val snackbar = remember { SnackbarHostState() }
    var term by remember { mutableStateOf<String?>(null) }
    var termLoaded by remember { mutableStateOf(false) }
    val books by produceState<List<Textbook>?>(initialValue = null) {
        repo.textbooksForCurrentTerm.collect { value = it }
    }

    LaunchedEffect(Unit) {
        term = runCatching { repo.currentTerm() }.getOrNull()
        termLoaded = true
    }

    val groups = remember(books) {
        books.orEmpty().groupBy { it.courseName }.toSortedMap()
    }
    val empty = books?.isEmpty() == true
    val noTerm = termLoaded && term.isNullOrBlank()

    PageScaffold(
        title = "教材",
        onBack = onBack,
        snackbar = snackbar,
        actions = { JwWebActionButton("教材", JwUrls.TEXTBOOK_QUERY) },
        loading = books == null,
        loadingText = "正在读取教材",
        empty = if (empty) {
            PageEmpty(
                title = "还没有教材数据",
                body = if (noTerm) {
                    "当前课表还没有学期号；导入课表后会自动抓取本学期教材。"
                } else {
                    "导入课表后会自动抓取本学期教材；也可以先去教务网页核对。"
                },
                actionLabel = if (noTerm) "去导入课表" else "在教务网页核对",
                onAction = {
                    if (noTerm) {
                        JwImportActivity.start(context)
                    } else {
                        JwImportActivity.startBrowse(context, "教材", JwUrls.TEXTBOOK_QUERY)
                    }
                },
            )
        } else null,
    ) {
        if (!term.isNullOrBlank()) {
            item {
                Text(
                    text = "$term 学期",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        items(groups.entries.toList(), key = { it.key }) { (course, list) ->
            AppCard(contentPadding = PaddingValues(0.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = course,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${list.size} 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                list.forEach { book ->
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    BookRow(book)
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Textbook) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        val sub = listOf(book.author, book.press, book.edition, book.isbn)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (book.price.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row {
                Text(
                    text = "定价 ${book.price}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
    }
}
