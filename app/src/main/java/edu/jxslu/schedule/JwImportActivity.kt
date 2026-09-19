package edu.jxslu.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import edu.jxslu.schedule.ui.jwvw.JwImportScreen

/**
 * 教务导入独立窗口。
 *
 * 根因：此前 jw_import 与三个 tab 同 NavHost，底栏显隐绑定路由——进入导入页的瞬间
 * 外层 Scaffold padding 变化，切换动画里的课表被突然拉伸。改成独立 Activity：
 * 新窗口直接覆盖，底层课表的布局完全不动；导入写库走 Graph 单例 + 响应式流，
 * 返回后课表自动刷新。Cookie 在 CookieManager 全局生效，登录会话不受影响。
 *
 * [EXTRA_MODE] 决定导入对象：课表（默认）或成绩（DESIGN §4.15）。
 */
class JwImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> JwImportMode.entries.firstOrNull { it.name == name } }
            ?: JwImportMode.Schedule
        setContent {
            JuwRoot {
                JwImportScreen(onBack = { finish() }, mode = mode)
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: JwImportMode = JwImportMode.Schedule) {
            val intent = Intent(context, JwImportActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
            context.startActivity(intent)
        }
    }
}
