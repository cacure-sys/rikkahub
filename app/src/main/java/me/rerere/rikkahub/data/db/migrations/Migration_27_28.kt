package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 压缩归档（message_node.archived）。
 *
 * 之前压缩是把被折叠的原文**删掉**（updateConversation 走 "删旧节点 + 插新节点"，
 * 同时 MessageFtsManager.indexConversation 会 DELETE FROM message_fts WHERE conversation_id），
 * 于是压缩是单向的：结果不满意、或想换提示词重压，原文已经不在了。
 *
 * 这一版加一个 archived 标记：
 * - 压缩只标记，不删除；请求构造（Conversation.currentMessages）跳过归档节点；
 * - UI 折叠显示，可展开、可一键撤销（恢复原文并丢弃那份摘要）；
 * - FTS 仍然索引全部节点，所以归档的正文照样能全文检索。
 *
 * 注意：defaultValue 必须与 ALTER 语句里的 DEFAULT 0 完全一致，
 * 否则 Room 校验会报 "Migration didn't properly handle: message_node"。
 * Kotlin 侧对应 @ColumnInfo("archived", defaultValue = "0")。
 */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing(
            table = "message_node",
            column = "archived",
            definition = "INTEGER NOT NULL DEFAULT 0",
        )
    }
}
