package com.github.kr328.clash.common.net

import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections
import java.util.Enumeration

/**
 * [NetworkInterface.getNetworkInterfaces] 在部分机型/时机下会抛出 [SocketException]，未捕获会导致界面直接崩溃。
 */
fun safeNetworkInterfaces(): Enumeration<NetworkInterface> {
    return try {
        NetworkInterface.getNetworkInterfaces() ?: Collections.emptyEnumeration()
    } catch (_: SocketException) {
        Collections.emptyEnumeration()
    }
}
