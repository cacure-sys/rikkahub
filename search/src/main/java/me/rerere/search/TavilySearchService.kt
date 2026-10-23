package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "TavilySearchService"

object TavilySearchService : SearchService<SearchServiceOptions.TavilyOptions> {
    override val name: String = "Tavily"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://app.tavily.com/home")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "search keywords, 2-8 terms, not a full sentence. " +
                            "Use English by default and keep proper nouns verbatim; " +
                            "use Chinese only when the topic exists only in Chinese " +
                            "(domestic policy, Chinese community, Chinese-only docs)."
                    )
                })
                put("topic", buildJsonObject {
                    put("type", "string")
                    put("description", "search topic (one of `general`, `news`, `finance`)")
                    put("enum", buildJsonArray {
                        add("general")
                        add("news")
                        add("finance")
                    })
                })
                if (options.allowDomains) {
                    put("include_domains", buildJsonObject {
                        put("type", "array")
                        put("description", "restrict to these domains, e.g. arxiv.org, official docs")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                }
                if (options.allowTimeRange) {
                    put("time_range", buildJsonObject {
                        put("type", "string")
                        put("description", "only results published in this window")
                        put("enum", buildJsonArray {
                            add("day")
                            add("week")
                            add("month")
                            add("year")
                        })
                    })
                }
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TavilyOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val topic = params["topic"]?.jsonPrimitive?.contentOrNull ?: "general"

            // Validate topic
            if (topic !in listOf("general", "news", "finance")) {
                error("topic must be one of `general`, `news`, `finance`")
            }

            val body = buildJsonObject {
                put("query", query)
                put("max_results", commonOptions.resultSize)
                put("search_depth", serviceOptions.depth.ifEmpty { "advanced" })
                put("topic", topic)
                // 综述是引擎对结果的二手转述，默认可关：模型拿到它就容易照抄，绕开网页原文
                if (serviceOptions.includeAnswer) {
                    put("include_answer", "advanced")
                } else {
                    put("include_answer", false)
                }
                put("include_images", true)

                // include_domains 在 schema 里是数组，但有的模型会塞成逗号串，两种都收
                params["include_domains"]?.let { raw ->
                    val domains: List<String> = parseDomainList(raw)
                    if (domains.isNotEmpty()) {
                        put("include_domains", buildJsonArray { domains.forEach { add(it) } })
                    }
                }
                params["time_range"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it in listOf("day", "week", "month", "year") }
                    ?.let { put("time_range", it) }
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())

            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<SearchResponse>(it)
                }

                return@withContext Result.success(
                    SearchResult(
                        // 关掉综述时即使引擎仍回了 answer（旧缓存/服务端兜底），也不往上下文里塞
                        answer = response.answer.takeIf { serviceOptions.includeAnswer },
                        items = response.results.map {
                            SearchResultItem(
                                title = it.title,
                                url = it.url,
                                text = it.content
                            )
                        },
                        images = response.images,
                    ))
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TavilyOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", buildJsonArray {
                    add(url)
                })
            }
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            val request = Request.Builder()
                .url("https://api.tavily.com/extract")
                .post(body.toString().toRequestBody())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val response = response.body.string().let {
                    json.decodeFromString<ScrapeResponse>(it)
                }
                return@withContext Result.success(
                    ScrapedResult(
                        urls = response.results.map {
                            ScrapedResultUrl(
                                url = it.url,
                                content = it.rawContent,
                            )
                        }
                    )
                )
            } else {
                error("response failed #${response.code}")
            }
        }
    }

    @Serializable
    data class SearchResponse(
        val query: String,
        val followUpQuestions: String? = null,
        val answer: String? = null,
        val images: List<String> = emptyList(),
        val results: List<TavilySearchService.SearchResultItem>,
    )

    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val content: String,
        val score: Double,
        val rawContent: String? = null
    )

    @Serializable
    data class ScrapeResponse(
        val results: List<ScrapedResultItem>,
    )

    @Serializable
    data class ScrapedResultItem(
        val url: String,
        @SerialName("raw_content")
        val rawContent: String,
    )
}

/**
 * 解析模型传进来的域名限制。
 *
 * schema 里 `include_domains` 是数组，但实测有的模型会塞成逗号串甚至换行串，
 * 所以先按数组解，失败再按分隔符切。两种都不是时返回空列表（等于不限制来源）。
 */
private fun parseDomainList(raw: JsonElement): List<String> {
    val fromArray = runCatching {
        raw.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrNull()
    val values: List<String> = fromArray ?: (
        raw.jsonPrimitive.contentOrNull
            ?.split(',', ' ', '\n')
            ?: emptyList()
        )
    return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
