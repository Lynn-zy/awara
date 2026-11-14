package me.rerere.awara.util

import java.security.MessageDigest

/**
 * 将字节数组转换为小写十六进制字符串
 *
 * @return 十六进制字符串
 */
fun ByteArray.toHex(): String {
    return joinToString("") { "%02x".format(it) }
}

/**
 * 计算字节数组的 SHA-1 摘要；每次调用独立创建 MessageDigest，保证多线程调用安全
 *
 * @return SHA-1 摘要字节数组
 */
fun ByteArray.sha1(): ByteArray {
    return MessageDigest.getInstance("SHA-1").digest(this)
}