package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.R
import org.koin.compose.koinInject

@Composable
internal fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (ImportedAssistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (ImportedAssistant) -> Unit
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: context.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

/**
 * 导入结果：助手本体 + 卡里内嵌的世界书（如果有）。
 *
 * 酒馆卡的 `character_book` 是整张卡的设定核心（DM 卡有 19 条、星铁卡有 7 条），
 * 旧实现直接把它丢了，导入后角色像个失忆的人。
 */
internal data class ImportedAssistant(
    val assistant: Assistant,
    val lorebook: Lorebook? = null,
)

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context, json: JsonObject, background: String?): ImportedAssistant
}

/**
 * v2 / v3 卡共用的解析逻辑。
 *
 * v3 只是在 v2 的 data 结构上加了 `creator_notes_multilingual`、`assets`、
 * `group_only_greetings` 等字段，核心字段一致，所以共用一套映射。
 */
private fun parseCardData(
    context: Context,
    data: JsonObject,
    background: String?,
): ImportedAssistant {
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_name_field))

    fun str(key: String): String? =
        data[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }

    val firstMessage = str("first_mes")
    val system = str("system_prompt")
    val description = str("description")
    val personality = str("personality")
    val scenario = str("scenario")
    val mesExample = str("mes_example")
    val postHistory = str("post_history_instructions")
    val characterNote = str("character_note")
    val creatorNotes = str("creator_notes")

    val prompt = buildString {
        appendLine("You are roleplaying as $name.")
        if (creatorNotes != null) {
            appendLine()
            appendLine("## Author's notes")
            appendLine(creatorNotes)
        }
        if (system != null) {
            appendLine()
            appendLine(system)
        }
        appendLine()
        appendLine("## Description of the character")
        appendLine(description ?: "Empty")
        appendLine()
        appendLine("## Personality of the character")
        appendLine(personality ?: "Empty")
        appendLine()
        appendLine("## Scenario")
        appendLine(scenario ?: "Empty")
        // 下面三段旧实现全丢了：示例对话决定说话风格，后置指令/作者注是持续约束
        if (mesExample != null) {
            appendLine()
            appendLine("## Example dialogue")
            appendLine(mesExample)
        }
        if (postHistory != null) {
            appendLine()
            appendLine("## Instructions to follow throughout the chat")
            appendLine(postHistory)
        }
        if (characterNote != null) {
            appendLine()
            appendLine("## Character note")
            appendLine(characterNote)
        }
    }

    // 多个开场白：酒馆会随机/手动选一个开局，这里全部带上，界面上可切换
    val greetings = buildList {
        firstMessage?.let { add(it) }
        data["alternate_greetings"]?.let { element ->
            runCatching {
                element.jsonArray.forEach { item ->
                    item.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                }
            }
        }
    }

    val lorebook = parseCharacterBook(name, data["character_book"])

    return ImportedAssistant(
        assistant = Assistant(
            name = name,
            presetMessages = greetings.map { UIMessage.assistant(it) },
            systemPrompt = prompt,
            background = background,
        ),
        lorebook = lorebook,
    )
}

/**
 * 把卡内嵌的 `character_book` 转成 RikkaHub 的 Lorebook。
 *
 * 注意**不能复用** `ExportSerializer.tryImportSillyTavern`：那是给独立世界书用的，
 * 结构与内嵌版不同——
 *
 * | | 内嵌 character_book | 独立世界书 |
 * |---|---|---|
 * | entries | List | Map |
 * | 关键词 | `keys` | `key` |
 * | 启用 | `enabled` | `disable`（取反） |
 * | 顺序 | `insertion_order` | `order` |
 * | 位置 | 字符串 `before_char`/`after_char` | 整数 0..4 |
 *
 * 没有对应概念的字段（`secondary_keys`、`selective`、`probability`、`group`、
 * `sticky`/`cooldown`/`delay` 等 ST 高级触发）只能丢弃。
 */
private fun parseCharacterBook(
    ownerName: String,
    element: JsonElement?,
): Lorebook? {
    val book = element?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
    val rawEntries = book["entries"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
    if (rawEntries.isEmpty()) return null

    val entries = rawEntries.mapNotNull { item ->
        val entry = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
        val content = entry["content"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        if (content.isNullOrEmpty()) return@mapNotNull null

        val ext = entry["extensions"]?.let { runCatching { it.jsonObject }.getOrNull() }

        fun extBool(key: String, def: Boolean = false): Boolean =
            ext?.get(key)?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: def
        fun extInt(key: String, def: Int): Int =
            ext?.get(key)?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: def
        fun entryBool(key: String, def: Boolean = false): Boolean =
            entry[key]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: def

        val keywords = entry["keys"]?.let { keys ->
            runCatching {
                keys.jsonArray.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim() }
                    .filter { it.isNotEmpty() }
            }.getOrElse {
                // 少数卡把 keys 写成了单个字符串
                keys.jsonPrimitiveOrNull?.contentOrNull
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            }
        } ?: emptyList()

        val constant = entryBool("constant")
        if (keywords.isEmpty() && !constant) return@mapNotNull null

        PromptInjection.RegexInjection(
            name = entry["comment"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: keywords.firstOrNull()
                ?: "Entry",
            enabled = entryBool("enabled", true),
            priority = extInt("insertion_order", entry["insertion_order"]?.jsonPrimitiveOrNull?.contentOrNull?.toIntOrNull() ?: 100),
            position = when (entry["position"]?.jsonPrimitiveOrNull?.contentOrNull) {
                "before_char" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
                "after_char" -> InjectionPosition.AFTER_SYSTEM_PROMPT
                else -> InjectionPosition.AFTER_SYSTEM_PROMPT
            },
            content = content,
            injectDepth = extInt("depth", 4),
            role = when (extInt("role", 0)) {
                1 -> MessageRole.USER
                2 -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            },
            keywords = keywords,
            useRegex = entryBool("use_regex"),
            caseSensitive = extBool("case_sensitive"),
            scanDepth = extInt("scan_depth", 4),
            constantActive = constant,
        )
    }

    if (entries.isEmpty()) return null
    return Lorebook(
        name = book["name"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ownerName,
        description = "",
        enabled = true,
        entries = entries,
    )
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context, json: JsonObject, background: String?): ImportedAssistant {
        val data = json["data"]?.jsonObject
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        return parseCardData(context, data, background)
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context, json: JsonObject, background: String?): ImportedAssistant {
        val data = json["data"]?.jsonObject
            ?: error(context.getString(R.string.assistant_importer_missing_data_field))
        return parseCardData(context, data, background)
    }
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser()
).associateBy { it.specName }

private fun parseAssistantFromJson(
    context: Context,
    json: JsonObject,
    background: String?,
): ImportedAssistant {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
        ?: error(context.getString(R.string.assistant_importer_missing_spec_field))
    val parser = TAVERN_PARSERS[spec] ?: error(context.getString(R.string.assistant_importer_unsupported_spec, spec))
    return parser.parse(context = context, json = json, background = background)
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (ImportedAssistant) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    // getTavernCharacterMeta 已经解出 JSON 明文，这里不要再 base64 解一遍
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { json ->
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val json = Json.parseToJsonElement(jsonString).jsonObject
        val imported = parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        onImport(imported)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
