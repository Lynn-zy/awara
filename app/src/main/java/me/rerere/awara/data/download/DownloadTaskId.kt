package me.rerere.awara.data.download

import java.util.UUID

/**
 * 强类型下载任务 UUID 标识符。
 *
 * 将标准 36 位 UUID 合法性校验收敛至单一入口 [parse] / [parseOrNull]，
 * 消除 `DownloadParts` 与 `DownloadManager` 跨层重复正则及 `UUID.fromString` 校验，
 * 同时彻底阻断文件路径穿越风险。
 */
@JvmInline
value class DownloadTaskId private constructor(val value: String) {
    /** 转换为标准 [UUID] 实例，供 WorkManager 等系统组件直接调用。 */
    fun toUuid(): UUID = UUID.fromString(value)

    override fun toString(): String = value

    companion object {
        /** 生成新的随机下载任务 ID。 */
        fun random(): DownloadTaskId = DownloadTaskId(UUID.randomUUID().toString())

        /**
         * 解析并校验标准 36 位 UUID 字符串。
         *
         * @throws IllegalArgumentException 当输入为空白、包含非规范字符或长度不匹配时抛出
         */
        fun parse(raw: String): DownloadTaskId =
            parseOrNull(raw) ?: throw IllegalArgumentException("无效的下载任务 ID")

        /** 解析标准 36 位 UUID 字符串，格式非法时返回 `null`。 */
        fun parseOrNull(raw: String?): DownloadTaskId? {
            if (raw.isNullOrBlank()) return null
            val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
            if (!parsed.toString().equals(raw, ignoreCase = true)) return null
            return DownloadTaskId(raw)
        }
    }
}
