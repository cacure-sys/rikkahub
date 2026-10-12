package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 自动压缩：ConversationEntity 增加 summary_covered_count（摘要已覆盖的消息条数，
 * 用于自动压缩触发判断）。对应 ConversationEntity.summaryCoveredCount。
 *
 * 这一步同时承担"接住旧私设库"的补偿职责：
 * 旧的私设版自行把版本号推到 26 时，其 schema 基础还是官方 24（那时官方尚未改到 25），
 * 所以它**没有** workspaces.shell_compatibility_mode。从这种库升到 27 不会经过 25->26，
 * 必须在这里补齐官方 24->25 的那一列，否则 Room 迁移后校验会失败
 * （Migration didn't properly handle: workspaces）。
 *
 * 同理，若来源是"官方开发版占用的 26 号"（无 summary_cache），这里也一并补齐。
 */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing(
            table = "ConversationEntity",
            column = "summary_covered_count",
            definition = "INTEGER NOT NULL DEFAULT 0",
        )
        // 补偿官方 24->25 的 schema 变更（旧私设库缺这一列）
        db.addColumnIfMissing(
            table = "workspaces",
            column = "shell_compatibility_mode",
            definition = "INTEGER NOT NULL DEFAULT 0",
        )
        // 防御：若来源库占用了 26 号却没有 summary_cache，也补齐
        db.addColumnIfMissing(
            table = "ConversationEntity",
            column = "summary_cache",
            definition = "TEXT NOT NULL DEFAULT ''",
        )
    }
}
