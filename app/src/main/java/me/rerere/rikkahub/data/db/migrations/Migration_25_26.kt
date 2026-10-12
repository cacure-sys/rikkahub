package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 自动压缩：ConversationEntity 增加 summary_cache（摘要缓存，只读拼装用，原文历史保留）。
 *
 * 对应 ConversationEntity.summaryCache / Conversation.summaryCache。
 * 兼容来源：官方 2.5.6（DB 25）以及更早的官方版本（先经过 24->25）。
 */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing(
            table = "ConversationEntity",
            column = "summary_cache",
            definition = "TEXT NOT NULL DEFAULT ''",
        )
    }
}
