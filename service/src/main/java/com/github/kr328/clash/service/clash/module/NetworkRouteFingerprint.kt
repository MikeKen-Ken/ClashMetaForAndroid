package com.github.kr328.clash.service.clash.module

internal data class NetworkRouteFingerprint(
    val interfaceName: String?,
    val linkAddresses: Set<String>,
    val routes: Set<String>,
    val mtu: Int,
    val nat64Prefix: String?,
)

internal fun routeRequiresRecovery(old: NetworkRouteFingerprint?, new: NetworkRouteFingerprint?): Boolean {
    if (old == null || new == null) return old != new
    return old.interfaceName != new.interfaceName || old.routes != new.routes ||
        !new.linkAddresses.containsAll(old.linkAddresses) || old.mtu != new.mtu ||
        old.nat64Prefix != new.nat64Prefix
}
