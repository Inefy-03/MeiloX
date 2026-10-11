package com.ljyh.mei.ui.screen.main.home

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder

internal fun parseRecommendationSongIds(value: String?): List<String> = runCatching {
    val array = value?.let(JsonParser::parseString)?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray ?: return@runCatching emptyList()
    array.mapNotNull { element ->
        element.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
            ?.takeIf { it.isString || it.isNumber }
            ?.asString?.positiveId()?.toString()
    }.distinct()
}.getOrDefault(emptyList())

internal fun parseRecommendationPlaylistId(action: String?, playBtnData: Any?): Long? =
    playlistIdFromUri(action) ?: playlistIdFromUri(playButtonDetailUrl(playBtnData))

private fun playlistIdFromUri(value: String?): Long? = runCatching {
    val uri = value?.trim()?.takeIf(String::isNotEmpty)?.let(::URI) ?: return@runCatching null
    if (!uri.scheme.equals("orpheus", ignoreCase = true) || uri.userInfo != null || uri.port != -1) {
        return@runCatching null
    }
    when {
        uri.host.equals("playlist", ignoreCase = true) -> {
            val path = uri.path.orEmpty()
            if (!path.startsWith('/')) return@runCatching null
            path.removePrefix("/").removeSuffix("/").positiveId()
        }
        uri.host.equals("nm", ignoreCase = true) && uri.path?.removeSuffix("/") == "/playlist/detail" -> {
            uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
                val pair = parameter.split('=', limit = 2)
                if (pair.size == 2 && pair[0].decodeQuery() == "id") pair[1].decodeQuery() else null
            }.singleOrNull()?.positiveId()
        }
        else -> null
    }
}.getOrNull()

private fun playButtonDetailUrl(value: Any?): String? = runCatching {
    val details = when (value) {
        is String -> JsonParser.parseString(value).takeIf(JsonElement::isJsonObject)?.asJsonObject
        is JsonObject -> value
        is Map<*, *> -> return@runCatching value["detailUrl"] as? String
        else -> null
    }
    details?.get("detailUrl")?.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive
        ?.takeIf { it.isString }?.asString
}.getOrNull()

private fun String.decodeQuery(): String = URLDecoder.decode(this, "UTF-8")

private fun String.positiveId(): Long? = trim()
    .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
    ?.toLongOrNull()?.takeIf { it > 0 }
