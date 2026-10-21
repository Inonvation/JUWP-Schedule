package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import edu.jxslu.schedule.domain.InboxKind
import edu.jxslu.schedule.domain.InboxMessage
import kotlinx.coroutines.flow.Flow

/**
 * 收件箱消息（DESIGN §4.38，表 `inbox_messages`，DB v18）。
 *
 * `dedupeKey` 唯一索引是投递去重的兜底：并发同键投递在 SQL 层也只会留一行。
 * 实体列**不写 Kotlin 默认值**（同 `CourseSelectionEntity` 的纪律：构造默认值会进
 * Room 预期 schema 的 DEFAULT，迁移建表就得逐字带上，v7→v8、v13→v14 的老坑）；
 * 可空列（readAt/deletedAt/route/focusItemId）在 SQLite 侧**不带 NOT NULL**、
 * 迁移里**不带 DEFAULT**（v14→v15 的口径）。
 * 索引名由 Room 生成：`index_inbox_messages_dedupeKey`（unique）与
 * `index_inbox_messages_createdAt`，迁移的 CREATE INDEX 必须逐字对齐。
 */
@Entity(
    tableName = "inbox_messages",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index("createdAt"),
    ],
)
data class InboxMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val dedupeKey: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    /** null = 未读。 */
    val readAt: Long?,
    /** 非 null = 用户已删（软删：重投不复活，30 天后物理清除）。 */
    val deletedAt: Long?,
    /** 点击跳转的二级页（SubpageScreen 枚举名），解析不到就不跳。 */
    val route: String?,
    val focusItemId: String?,
) {
    fun toDomain(): InboxMessage = InboxMessage(
        id = id,
        kind = runCatching { InboxKind.valueOf(kind) }.getOrDefault(InboxKind.EXAM),
        dedupeKey = dedupeKey,
        title = title,
        body = body,
        createdAt = createdAt,
        readAt = readAt,
        deletedAt = deletedAt,
        route = route,
        focusItemId = focusItemId,
    )

    companion object {
        fun of(msg: InboxMessage): InboxMessageEntity = InboxMessageEntity(
            id = msg.id,
            kind = msg.kind.name,
            dedupeKey = msg.dedupeKey,
            title = msg.title,
            body = msg.body,
            createdAt = msg.createdAt,
            readAt = msg.readAt,
            deletedAt = msg.deletedAt,
            route = msg.route,
            focusItemId = msg.focusItemId,
        )
    }
}

@Dao
interface InboxDao {

    /** 可见消息（未软删），最新在前；LIMIT 与保留策略的总量上限同一个数。 */
    @Query(
        "SELECT * FROM inbox_messages WHERE deletedAt IS NULL " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    fun observeMessages(limit: Int): Flow<List<InboxMessageEntity>>

    /** 未读数（驱动今日页「+」图标与菜单行的角标）。 */
    @Query("SELECT COUNT(*) FROM inbox_messages WHERE deletedAt IS NULL AND readAt IS NULL")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM inbox_messages WHERE dedupeKey = :key LIMIT 1")
    suspend fun findByKey(key: String): InboxMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InboxMessageEntity)

    /** 重投命中未删行：刷新内容 + 重新置未读（[edu.jxslu.schedule.domain.InboxRules.Refresh]）。 */
    @Query(
        "UPDATE inbox_messages SET title = :title, body = :body, route = :route, " +
            "focusItemId = :focusItemId, createdAt = :now, readAt = NULL " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun refreshAndReopen(id: Long, title: String, body: String, route: String?, focusItemId: String?, now: Long)

    /** 静默重投（账单类，2026-10-21）：只刷新内容，已读状态保持——不产生未读红点。 */
    @Query(
        "UPDATE inbox_messages SET title = :title, body = :body, route = :route, " +
            "focusItemId = :focusItemId, createdAt = :now " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun refreshSilent(id: Long, title: String, body: String, route: String?, focusItemId: String?, now: Long)

    /** 重投命中已删行：只刷新内容，保持已删、不复活（[edu.jxslu.schedule.domain.InboxRules.RefreshDeleted]）。 */
    @Query(
        "UPDATE inbox_messages SET title = :title, body = :body, route = :route, " +
            "focusItemId = :focusItemId, createdAt = :now " +
            "WHERE id = :id AND deletedAt IS NOT NULL",
    )
    suspend fun refreshDeleted(id: Long, title: String, body: String, route: String?, focusItemId: String?, now: Long)

    @Query("UPDATE inbox_messages SET readAt = :readAt WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: Long, readAt: Long)

    @Query("UPDATE inbox_messages SET readAt = :readAt WHERE deletedAt IS NULL AND readAt IS NULL")
    suspend fun markAllRead(readAt: Long)

    /** 按来源标已读（调课差异处置完成后，那条「有变动」的事实已经了结）。 */
    @Query("UPDATE inbox_messages SET readAt = :readAt WHERE kind = :kind AND deletedAt IS NULL AND readAt IS NULL")
    suspend fun markKindRead(kind: String, readAt: Long)

    /** 批量软删（多选删除）。 */
    @Query("UPDATE inbox_messages SET deletedAt = :deletedAt WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun softDelete(ids: List<Long>, deletedAt: Long)

    /** 状态消解（余额回升）：状态键的消息整个移除。 */
    @Query("DELETE FROM inbox_messages WHERE dedupeKey = :key")
    suspend fun hardDeleteByKey(key: String)

    /** 清理计划的全量输入（量级 = 保留上限 200 条 + 软删未到期的一小截）。 */
    @Query("SELECT * FROM inbox_messages")
    suspend fun all(): List<InboxMessageEntity>

    @Query("DELETE FROM inbox_messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
