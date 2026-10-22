package edu.jxslu.schedule.data.media

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.GalleryPaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 一次性把旧相册目录 `Pictures/水贝贝` 迁到 `Pictures/JUWPHub`（2026-10-23 改名）。
 *
 * 失败不删旧文件：迁移是 best-effort，宁可留两份，不能丢图。
 * - API 29+：改 MediaStore `RELATIVE_PATH`，content uri 不变，旧待焚毁 key 继续有效；
 * - API 26–28：File rename，失败 copy + delete；成功后把 `ebikePendingDelete` 里的旧绝对路径
 *   改写成新路径。
 *
 * 成功后写 `gallery_migrated_v1`；失败下次冷启动再试（24h 闸门，避免每次启动都扫）。
 */
object GalleryMigration {
    private const val PREFS = "maintenance_gates"
    private const val KEY_MIGRATED = "gallery_migrated_v1"
    private const val KEY_LAST_ATTEMPT = "last_gallery_migration_attempt"
    private const val RETRY_INTERVAL_MS = 24L * 60 * 60 * 1000
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /**
     * @return true = 已迁移或无需迁移；false = 这次没搬完，下次冷启动再试。
     */
    suspend fun migrateIfNeeded(context: Context, prefs: DisplayPrefsStore): Boolean {
        val gate = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (gate.getBoolean(KEY_MIGRATED, false)) return true
        // API 26–28 必须先有写外存权限；没授权时不要消耗 24h 闸门，用户授权后下次启动立刻跑。
        if (Build.VERSION.SDK_INT <= 28 && !hasLegacyWritePermission(context)) return false
        val now = System.currentTimeMillis()
        if (now - gate.getLong(KEY_LAST_ATTEMPT, 0L) < RETRY_INTERVAL_MS) return false

        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 29) migrateMediaStore(context) else migrateFiles()
        }.getOrElse { return false }

        var pendingKeysOk = true
        if (result.movedFilePaths.isNotEmpty()) {
            pendingKeysOk = runCatching {
                prefs.updateEbikePendingDelete { keys ->
                    keys.map { key ->
                        val parsed = EbikeQr.parsePendingKey(key) ?: return@map key
                        if (parsed.first != EbikeQr.PendingKind.FilePath) return@map key
                        val newPath = result.movedFilePaths[parsed.second] ?: return@map key
                        EbikeQr.pendingFileKey(newPath)
                    }.toSet()
                }
            }.isSuccess
        }

        if (result.completed && pendingKeysOk) {
            gate.edit().putBoolean(KEY_MIGRATED, true).apply()
        } else {
            gate.edit().putLong(KEY_LAST_ATTEMPT, now).apply()
        }
        android.util.Log.i(
            "GalleryMigration",
            "completed=${result.completed}, moved=${result.movedFilePaths.size}, sdk=${Build.VERSION.SDK_INT}",
        )
        return result.completed
    }

    private fun hasLegacyWritePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun migrateMediaStore(context: Context): MigrationResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        // 只查本应用自己写的媒体：OWNER_PACKAGE_NAME 让查询在无 READ_MEDIA_IMAGES 时也能成立，
        // 同时避免把别的 App 放进同名目录的图搬走。
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
        val args = arrayOf(
            "Pictures/${GalleryPaths.LEGACY_SUBDIR}%",
            context.packageName,
        )
        var completed = true

        resolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val oldPath = cursor.getString(pathCol).orEmpty()
                val isLegacy = oldPath == "Pictures/${GalleryPaths.LEGACY_SUBDIR}" ||
                    oldPath.startsWith("Pictures/${GalleryPaths.LEGACY_SUBDIR}/")
                if (!isLegacy) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                val values = ContentValues().apply {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/${GalleryPaths.CURRENT_SUBDIR}/",
                    )
                }
                val moved = runCatching {
                    resolver.update(uri, values, null, null) > 0
                }.getOrDefault(false)
                if (!moved) completed = false
            }
        } ?: return MigrationResult(completed = false)

        return MigrationResult(completed = completed)
    }

    @Suppress("DEPRECATION")
    private fun migrateFiles(): MigrationResult {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return MigrationResult(completed = false)
        }
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val source = File(pictures, GalleryPaths.LEGACY_SUBDIR)
        if (!source.isDirectory) return MigrationResult(completed = true)
        val target = File(pictures, GalleryPaths.CURRENT_SUBDIR)
        if (!target.exists() && !target.mkdirs()) return MigrationResult(completed = false)

        var completed = true
        val moved = mutableMapOf<String, String>()
        source.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.extension.lowercase() !in IMAGE_EXTENSIONS) return@forEach
            val dest = File(target, file.name)
            if (dest.exists()) {
                // 同名文件不覆盖；留给用户/下次人工处理，不算搬完
                completed = false
                return@forEach
            }
            val renamed = runCatching { file.renameTo(dest) }.getOrDefault(false)
            if (renamed) {
                moved[file.absolutePath] = dest.absolutePath
                return@forEach
            }
            val copied = runCatching {
                FileInputStream(file).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied) {
                runCatching { dest.delete() }
                completed = false
                return@forEach
            }
            if (file.delete()) {
                moved[file.absolutePath] = dest.absolutePath
            } else {
                // 复制成功但旧文件删不掉：删掉新副本，避免相册里两份；旧文件留着下次再试
                runCatching { dest.delete() }
                completed = false
            }
        }
        runCatching { if (source.listFiles()?.isEmpty() == true) source.delete() }
        return MigrationResult(completed = completed, movedFilePaths = moved)
    }

    private data class MigrationResult(
        val completed: Boolean,
        val movedFilePaths: Map<String, String> = emptyMap(),
    )
}
