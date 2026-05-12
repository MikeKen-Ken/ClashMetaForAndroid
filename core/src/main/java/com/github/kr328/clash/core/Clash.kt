package com.github.kr328.clash.core

import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

object Clash {
    enum class OverrideSlot {
        Persist, Session
    }

    private val ConfigurationOverrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Persist 覆写槽读写互斥：与 [mutatePersistOverride]、[withPersistOverrideSync] 共用，防止服务内读改写与 Binder 整包替换交叉导致丢失更新。 */
    private val persistOverrideSync = Any()

    private fun decodePersistOverrideOrEmpty(json: String): ConfigurationOverride {
        return try {
            ConfigurationOverrideJson.decodeFromString(
                ConfigurationOverride.serializer(),
                json
            )
        } catch (e: Exception) {
            ConfigurationOverride()
        }
    }

    /**
     * 在持有 Persist 锁的情况下执行 [block]（可重入）。
     * 供服务进程内 ClashManager 将「读前态 → 分类 → 写入」收束为原子步骤。
     */
    fun <T> withPersistOverrideSync(block: () -> T): T {
        synchronized(persistOverrideSync) {
            return block()
        }
    }

    /**
     * 原子读改写 Persist 覆写（仅改内存中的对象再写回），避免与整包 [patchOverride] 交叉覆盖用户设置。
     */
    fun mutatePersistOverride(transform: (ConfigurationOverride) -> Unit) {
        synchronized(persistOverrideSync) {
            val current = decodePersistOverrideOrEmpty(
                Bridge.nativeReadOverride(OverrideSlot.Persist.ordinal)
            )
            transform(current)
            Bridge.nativeWriteOverride(
                OverrideSlot.Persist.ordinal,
                ConfigurationOverrideJson.encodeToString(
                    ConfigurationOverride.serializer(),
                    current
                )
            )
        }
    }

    fun reset() {
        Bridge.nativeReset()
    }

    fun forceGc() {
        Bridge.nativeForceGc()
    }

    fun suspendCore(suspended: Boolean) {
        Bridge.nativeSuspend(suspended)
    }

    fun queryTunnelState(): TunnelState {
        val json = Bridge.nativeQueryTunnelState()

        return Json.decodeFromString(TunnelState.serializer(), json)
    }

    fun queryTrafficNow(): Traffic {
        return Bridge.nativeQueryTrafficNow()
    }

    fun queryTrafficTotal(): Traffic {
        return Bridge.nativeQueryTrafficTotal()
    }

    fun notifyDnsChanged(dns: List<String>) {
        Bridge.nativeNotifyDnsChanged(dns.toSet().joinToString(separator = ","))
    }

    fun notifyTimeZoneChanged(name: String, offset: Int) {
        Bridge.nativeNotifyTimeZoneChanged(name, offset)
    }

    fun notifyInstalledAppsChanged(uids: List<Pair<Int, String>>) {
        val uidList = uids.joinToString(separator = ",") { "${it.first}:${it.second}" }

        Bridge.nativeNotifyInstalledAppChanged(uidList)
    }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        markSocket: (Int) -> Boolean,
        querySocketUid: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress) -> Int
    ) {
        Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                markSocket(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                return querySocketUid(
                    protocol,
                    parseInetSocketAddress(source),
                    parseInetSocketAddress(target)
                )
            }
        })
    }

    fun stopTun() {
        Bridge.nativeStopTun()
    }

    fun startHttp(listenAt: String): String? {
        return Bridge.nativeStartHttp(listenAt)
    }

    fun stopHttp() {
        Bridge.nativeStopHttp()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val names = Json.Default.decodeFromString(
            JsonArray.serializer(),
            Bridge.nativeQueryGroupNames(excludeNotSelectable)
        )

        return names.map {
            require(it.jsonPrimitive.isString)

            it.jsonPrimitive.content
        }
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return Bridge.nativeQueryGroup(name, sort.name)
            ?.let { Json.Default.decodeFromString(ProxyGroup.serializer(), it) }
            ?: ProxyGroup(Proxy.Type.Unknown, emptyList(), "")
    }

    fun healthCheck(name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheck(this, name)
        }
    }

    fun setHealthCheckWorkerLimit(limit: Int) {
        Bridge.nativeSetHealthCheckWorkerLimit(limit)
    }

    fun healthCheckWithTimeout(name: String, timeoutMs: Int, concurrency: Int): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheckWithTimeout(this, name, timeoutMs, concurrency)
        }
    }

    fun healthCheckAll() {
        Bridge.nativeHealthCheckAll()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return Bridge.nativePatchSelector(selector, name)
    }

    /** 对齐 Hub PATCH log-level：仅更新运行时日志级别，不落整库 reload */
    fun patchRuntimeLogLevel(levelKeyLowercaseOrYamlName: String): Boolean {
        return Bridge.nativePatchRuntimeLogLevel(levelKeyLowercaseOrYamlName)
    }

    fun patchConnectivityJsonSubset(jsonPayload: String): Boolean {
        return Bridge.nativePatchConnectivityJson(jsonPayload)
    }

    fun clearAllManualSelections() {
        Bridge.nativeClearAllManualSelections()
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
        reportStatus: (FetchStatus) -> Unit
    ): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeFetchAndValid(
                object : FetchCallback {
                    override fun report(statusJson: String) {
                        reportStatus(
                            Json.Default.decodeFromString(
                                FetchStatus.serializer(),
                                statusJson
                            )
                        )
                    }

                    override fun complete(error: String?) {
                        if (error != null)
                            completeExceptionally(ClashException(error))
                        else
                            complete(Unit)
                    }
                },
                path.absolutePath,
                url,
                force
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    fun queryProviders(): List<Provider> {
        val providers =
            Json.Default.decodeFromString(JsonArray.serializer(), Bridge.nativeQueryProviders())

        return List(providers.size) {
            Json.Default.decodeFromJsonElement(Provider.serializer(), providers[it])
        }
    }

    fun updateProvider(type: Provider.Type, name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeUpdateProvider(this, type.toString(), name)
        }
    }

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride {
        val json = if (slot == OverrideSlot.Persist) {
            synchronized(persistOverrideSync) {
                Bridge.nativeReadOverride(slot.ordinal)
            }
        } else {
            Bridge.nativeReadOverride(slot.ordinal)
        }
        return decodePersistOverrideOrEmpty(json)
    }

    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride) {
        val encoded = ConfigurationOverrideJson.encodeToString(
            ConfigurationOverride.serializer(),
            configuration
        )
        if (slot == OverrideSlot.Persist) {
            synchronized(persistOverrideSync) {
                Bridge.nativeWriteOverride(slot.ordinal, encoded)
            }
        } else {
            Bridge.nativeWriteOverride(slot.ordinal, encoded)
        }
    }

    fun clearOverride(slot: OverrideSlot) {
        if (slot == OverrideSlot.Persist) {
            synchronized(persistOverrideSync) {
                Bridge.nativeClearOverride(slot.ordinal)
            }
        } else {
            Bridge.nativeClearOverride(slot.ordinal)
        }
    }

    fun queryConfiguration(): UiConfiguration {
        return Json.Default.decodeFromString(
            UiConfiguration.serializer(),
            Bridge.nativeQueryConfiguration()
        )
    }

    fun queryRuntimeYamlByProfile(profilePath: File): String {
        return Bridge.nativeQueryRuntimeYamlByProfile(profilePath.absolutePath)
    }

    fun queryConnections(): ConnectionsSnapshot {
        return Json.Default.decodeFromString(
            ConnectionsSnapshot.serializer(),
            Bridge.nativeQueryConnections()
        )
    }

    fun closeConnection(id: String): Boolean {
        return Bridge.nativeCloseConnection(id)
    }

    fun closeAllConnections() {
        Bridge.nativeCloseAllConnections()
    }

    fun closeConnectionsExcludingDirect() {
        Bridge.nativeCloseConnectionsExcludingDirect()
    }

    /** 关闭链路中包含指定代理组的连接（手动切换组内节点后调用，使流量走新选中节点）。 */
    fun closeConnectionsUsingProxyGroup(group: String) {
        Bridge.nativeCloseConnectionsUsingProxyGroup(group)
    }

    /** 关闭 source 为私网地址的连接（与电脑端关闭 allow-lan 时的行为对齐）。 */
    fun closeLanConnections() {
        Bridge.nativeCloseLanConnections()
    }

    /**
     * 清空 fake-ip 映射表。
     * 在手动更新规则后调用，可立即修正受旧 fake-ip 影响的路由决策。
     */
    fun flushFakeIpCache() {
        val error = Bridge.nativeFlushFakeIpCache()
        if (error != null) throw ClashException(error)
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    trySend(Json.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }
}