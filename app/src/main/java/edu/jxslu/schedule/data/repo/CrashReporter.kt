package edu.jxslu.schedule.data.repo

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留痕（2026-10-02，体检第三批）：未捕获异常落到 `filesDir/crash/`，**只写本机、
 * 不联网、不上传**（DESIGN §5 隐私口径——堆栈里可能带包名/版本，不能进任何网络路径）。
 *
 * 两条记录路径：
 * 1. [installUncaughtHandler]：JVM 级兜底，接住所有协程外的崩溃（链式调用原 handler，
 *    系统弹窗与崩溃上报行为不变）；
 * 2. [logCoroutine]：`Graph.appScope` 的 CoroutineExceptionHandler 调它——协程异常被
 *    SupervisorJob 吞掉时这里至少留一份文件。
 *
 * 保留最近 [MAX_FILES] 份，更早的顺手清掉（崩溃不是日志系统，不无限堆积）。
 * 「我的 → 关于」的导出入口读 [crashDir] 里的文件列表。
 */
object CrashReporter {

    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 5

    /**
     * **毫秒**精度：秒精度下同 tag 同秒第二份会与第一份同名互覆（现实路径：appScope
     * 多个 launch 同帧炸同一异常）；文件名含毫秒后字典序即严格时间序，清理排序也稳了。
     */
    private const val TIME_FORMAT = "yyyyMMdd-HHmmss.SSS"

    @Volatile
    private var installed = false

    /** 崩溃目录（不建目录，首份崩溃时才建）。 */
    fun crashDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * 安装 JVM 级未捕获异常兜底。**只在 Application.onCreate 调一次**（fail-fast 防将来
     * 多个调用点把 handler 链包成 N 层）；链式调用原 handler（previous 为 null = 没有系统
     * handler，跳过链式即正确），落盘失败绝不影响原崩溃流程。
     */
    fun installUncaughtHandler(context: Context) {
        check(!installed) { "installUncaughtHandler 只允许调用一次（JuwApplication.onCreate）" }
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(context, "uncaught", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 协程作用域的 handler 用的落盘入口（不终止进程，只留痕）。 */
    fun logCoroutine(context: Context, tag: String, error: Throwable) {
        runCatching { write(context, tag, error) }
    }

    /** 列出留痕文件（新在前）；「关于」页导出入口用。 */
    fun listFiles(context: Context): List<File> =
        crashDir(context).listFiles()?.sortedByDescending { it.name }.orEmpty()

    private fun write(context: Context, tag: String, error: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat(TIME_FORMAT, Locale.ROOT).format(Date())
        val file = File(dir, "$tag-$stamp.txt")
        StringWriter().use { buffer ->
            PrintWriter(buffer).use { printer ->
                printer.println("time: ${Date()}")
                printer.println("thread: ${Thread.currentThread().name}")
                printer.println("version: ${context.packageName}")
                printer.println()
                error.printStackTrace(printer)
            }
            file.writeText(buffer.toString())
        }
        // 超出保留数就删最旧的：按 lastModified 排序并**排除刚写的这份**——
        // 不排除的话高频崩溃下 drop(5) 可能砍到最新一份
        listFiles(context)
            .filterNot { it == file }
            .drop(MAX_FILES)
            .forEach { it.delete() }
    }
}
