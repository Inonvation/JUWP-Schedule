package edu.jxslu.schedule.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.session.SessionExpiryStore
import edu.jxslu.schedule.data.session.SessionService

/**
 * 进页提示：该功能在别处已判定登录过期时，给一句「登录状态已过期，请重新登录」。
 *
 * 标记由各仓库在**判定失效的那一刻**写入（[SessionExpiryStore]），这里只负责消费：
 * - 未登录且标记在 → 提示一次并取走标记（同一轮不重复弹）；
 * - 已登录（刚重登成功）→ 顺手清掉旧标记。
 *
 * **只在功能自己的页面调用**，不要在今日页的卡片里调：那会让「进这个界面」变成
 * 「一打开 App 就弹各家的过期提示」。用户主动退出登录走各仓库的 `logout()`，标记已清，
 * 不会误报。
 */
@Composable
fun SessionExpiredNotice(
    service: SessionService,
    loggedIn: Boolean,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(loggedIn) {
        val store = Graph.sessionExpiry(context)
        if (loggedIn) {
            store.clear(service)
        } else if (store.consume(service)) {
            scope.postNotice(snackbar, SessionExpiryStore.NOTICE_TEXT, NoticeTone.Warning)
        }
    }
}
