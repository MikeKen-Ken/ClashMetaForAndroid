package com.github.kr328.clash.common.net

/**
 * 与 clash-verge-rev `features/lan-devices/model.ts` 中 isLanSourceIp 规则一致，
 * 用于判断连接的 source IP 是否属于「局域网私网地址」语义。
 */
object LanSourceIp {

    fun isLanSourceIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val normalized = ip.trim().lowercase()
        if (
            normalized == "127.0.0.1" ||
            normalized == "::1" ||
            normalized == "0.0.0.0" ||
            normalized == "::" ||
            normalized.startsWith("ff")
        ) {
            return false
        }
        if (':' in normalized) {
            return normalized.startsWith("fc") ||
                normalized.startsWith("fd") ||
                normalized.startsWith("fe8") ||
                normalized.startsWith("fe9") ||
                normalized.startsWith("fea") ||
                normalized.startsWith("feb")
        }
        if (normalized.startsWith("169.254.")) return false
        if (normalized.startsWith("192.168.") || normalized.startsWith("10.")) return true
        val second = normalized.split('.').getOrNull(1)?.toIntOrNull() ?: -1
        return normalized.startsWith("172.") && second in 16..31
    }
}
