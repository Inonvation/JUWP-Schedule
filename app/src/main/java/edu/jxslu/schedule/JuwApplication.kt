package edu.jxslu.schedule

import android.app.Application
import edu.jxslu.schedule.data.repo.ScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class JuwApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val repo = Graph.repository(this)
        appScope.launch {
            // 仅保证节次与学期默认值；课表默认空，由教务导入
            repo.ensureDefaults()
        }
    }
}
