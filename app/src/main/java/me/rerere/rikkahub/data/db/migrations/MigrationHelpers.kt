package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移辅助：按"列不存在才加"执行 ALTER TABLE。
 *
 * 历史背景（为什么需要幂等）：
 * 本 fork 的"自动压缩"功能曾把 ConversationEntity 的两列加在 25 / 26 号版本上，
 * 而官方 2.5.6 恰好把 **25 号**用在了 `workspaces.shell_compatibility_mode`。
 * 同一个版本号在两边代表不同 schema —— 这正是"私设版备份导入官方版报
 * `A migration from 26 to 25 was required but not found`"的根因。
 *
 * 合并官方后以官方的 25 号为准，fork 的两列顺延为 25->26 / 26->27。
 * 因此运行时可能遇到三种来源库：
 *   1. 官方 25（有 shell_compatibility_mode，无 fork 两列）
 *   2. 旧的私设 26（有 fork 两列，**没有** shell_compatibility_mode）
 *   3. 更早的官方 24（都没有，但会先走官方的 autoMigration 24->25）
 * 所以每条迁移都必须容忍"列已存在"或"列缺失"，不能假定编号对应的 schema。
 */
internal fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

/** 列不存在才添加，避免"duplicate column name"导致整个迁移失败。 */
internal fun SupportSQLiteDatabase.addColumnIfMissing(
    table: String,
    column: String,
    definition: String,
) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }
}
