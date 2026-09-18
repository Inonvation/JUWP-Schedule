package edu.jxslu.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.jxslu.schedule.ui.jwvw.JwImportScreen

/**
 * 教务导入独立窗口。
 *
 * 根因：此前 jw_import 与三个 tab 同 NavHost，底栏显隐绑定路由——进入导入页的瞬间
 * 外层 Scaffold padding 变化，切换动画里的课表被突然拉伸。改成独立 Activity：
 * 新窗口直接覆盖，底层课表的布局完全不动；导入写库走 Graph 单例 + 响应式流，
 * 返回后课表自动刷新。Cookie 在 CookieManager 全局生效，登录会话不受影响。
 */
class JwImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                JwImportScreen(onBack = { finish() })
            }
        }
    }
}
