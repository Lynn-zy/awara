package me.rerere.awara.di

import android.content.Context
import android.os.LocaleList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.awara.R
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.util.JsonInstance
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 负责按照系统首选语言解析标签名称，并在没有翻译时回退到标签原始 ID。
 */
class TagI18NResolver(context: Context) {
    private val tagJson by lazy {
        context.resources.openRawResource(R.raw.tag).bufferedReader().use { reader ->
            JsonInstance.parseToJsonElement(reader.readText()).jsonObject
        }
    }

    private val tagTranslations: Map<String, Map<String, String>> by lazy {
        loadTranslations(tagJson)
    }

    private val supportedLocales: Array<String> by lazy {
        tagJson["supportedLocales"]?.jsonArray
            ?.map { locale -> locale.jsonPrimitive.content }
            ?.toTypedArray()
            ?: emptyArray()
    }

    /**
     * 返回与 APK 一致的显示文本：原始标签 ID 后追加翻译文本。
     */
    fun tagName(tag: Tag, locales: LocaleList): String = tagName(tag.id, locales)

    /**
     * 按标签 ID 和当前系统语言查找翻译；找不到时保留原始标签 ID。
     */
    fun tagName(tagId: String, locales: LocaleList): String {
        val matchedLocale = locales.getFirstMatch(supportedLocales)
        val translation = matchedLocale?.toLanguageTag()?.let { localeTag ->
            tagTranslations[tagId]?.get(localeTag)
        }
        return translation?.let { "$tagId / $it" } ?: tagId
    }

    private fun loadTranslations(json: JsonObject): Map<String, Map<String, String>> {
        val tags = json["tags"]?.jsonObject ?: return emptyMap()

        return tags.entries.flatMap { (canonicalId, tagElement) ->
            val tagObject = tagElement.jsonObject
            val aliases = tagObject["alias"]?.jsonArray
                ?.map { alias -> alias.jsonPrimitive.content }
                .orEmpty()
            val translations = tagObject["locales"]?.jsonObject
                ?.mapValues { (_, translation) -> translation.jsonPrimitive.content }
                .orEmpty()
            (aliases + canonicalId).map { tagId -> tagId to translations }
        }.toMap()
    }
}

val tagI18NModule = module {
    single {
        TagI18NResolver(androidContext())
    }
}
