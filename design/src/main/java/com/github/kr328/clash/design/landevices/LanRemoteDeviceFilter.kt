package com.github.kr328.clash.design.landevices

import com.github.kr328.clash.common.net.LanSourceIp
import com.github.kr328.clash.core.model.Connection
import java.net.NetworkInterface

/**
 * 与电脑端 clash-verge-rev 设备视图一致的过滤：只统计远端局域网客户端，
 * 排除本机网卡 IP 及带本机进程名的连接（如 adb 探测等噪音）。
 */
object LanRemoteDeviceFilter {

    /** 收集本机网卡地址（含 IPv4/IPv6），用于排除「本机发起」的连接。 */
    fun collectLocalInterfaceIps(): Set<String> {
        val out = mutableSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
        while (interfaces.hasMoreElements()) {
            val ni = interfaces.nextElement()
            if (ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr.isLoopbackAddress) continue
                val raw = addr.hostAddress?.trim() ?: continue
                out.add(raw)
                out.add(normalizeIpKey(raw))
            }
        }
        return out
    }

    fun isRemoteLanClient(conn: Connection, localInterfaceIps: Set<String>): Boolean {
        val sourceIp = conn.metadata?.sourceIP?.trim().orEmpty()
        if (!LanSourceIp.isLanSourceIp(sourceIp)) return false
        val key = normalizeIpKey(sourceIp)
        if (localInterfaceIps.contains(sourceIp) || localInterfaceIps.contains(key)) return false
        val proc = conn.metadata?.process?.trim().orEmpty()
        if (proc.isNotEmpty()) return false
        return true
    }

    private fun normalizeIpKey(ip: String): String {
        val t = ip.trim()
        val noZone = if ('%' in t) t.substringBefore('%') else t
        return if (':' in noZone) noZone.lowercase() else noZone
    }
}
