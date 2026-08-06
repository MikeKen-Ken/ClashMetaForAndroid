package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class Proxy(
    val name: String,
    val title: String,
    val subtitle: String,
    val type: Type,
    val delay: Int,
) : Parcelable {
    /**
     * Wire format uses stable type names (not ordinals) so unknown core types map to [Unknown]
     * instead of crashing when the service/core advances ahead of UI models.
     */
    @Serializable(with = ProxyTypeSerializer::class)
    @Suppress("unused")
    enum class Type(val group: Boolean) {
        Direct(false),
        Reject(false),
        RejectDrop(false),
        Compatible(false),
        Pass(false),

        Shadowsocks(false),
        ShadowsocksR(false),
        Snell(false),
        Socks5(false),
        Http(false),
        Vmess(false),
        Vless(false),
        Trojan(false),
        Hysteria(false),
        Hysteria2(false),
        Tuic(false),
        WireGuard(false),
        Dns(false),
        Ssh(false),
        Mieru(false),
        AnyTLS(false),
        Sudoku(false),


        Relay(true),
        Selector(true),
        Fallback(true),
        URLTest(true),
        LoadBalance(true),

        Masque(false),
        TrustTunnel(false),
        OpenVPN(false),
        Tailscale(false),
        GostRelay(false),

        Unknown(false);
    }

    object ProxyTypeSerializer : KSerializer<Type> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Proxy.Type", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Type) {
            encoder.encodeString(value.name)
        }

        override fun deserialize(decoder: Decoder): Type {
            val raw = decoder.decodeString()
            return Type.entries.find { it.name.equals(raw, ignoreCase = true) } ?: Type.Unknown
        }
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<Proxy> {
        override fun createFromParcel(parcel: Parcel): Proxy {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Proxy?> {
            return arrayOfNulls(size)
        }
    }
}
