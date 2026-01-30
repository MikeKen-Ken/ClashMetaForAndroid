package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionsSnapshot(
    val downloadTotal: Long = 0,
    val uploadTotal: Long = 0,
    val connections: List<Connection> = emptyList(),
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionsSnapshot> {
        override fun createFromParcel(parcel: Parcel): ConnectionsSnapshot {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionsSnapshot?> = arrayOfNulls(size)
    }
}

@Serializable
data class Connection(
    val id: String,
    val metadata: ConnectionMetadata? = null,
    val upload: Long = 0,
    val download: Long = 0,
    val start: String = "",
    val chains: String = "",
    @SerialName("providerChains") val providerChains: String = "",
    val rule: String = "",
    val rulePayload: String = "",
    val ruleDetail: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Connection> {
        override fun createFromParcel(parcel: Parcel): Connection {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<Connection?> = arrayOfNulls(size)
    }
}

@Serializable
data class ConnectionMetadata(
    val network: String = "",
    val host: String = "",
    val process: String = "",
    @SerialName("sourceIP") val sourceIP: String = "",
    @SerialName("destinationPort") val destinationPort: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ConnectionMetadata> {
        override fun createFromParcel(parcel: Parcel): ConnectionMetadata {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ConnectionMetadata?> = arrayOfNulls(size)
    }
}
