package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

@Serializable
data class DelayTestResult(
    val group: String,
    val tested: Int,
    val succeeded: Int,
    val failed: Int,
    val elapsedMs: Long,
    val error: String? = null,
) : Parcelable {
    val hasSuccess: Boolean
        get() = succeeded > 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<DelayTestResult> {
        override fun createFromParcel(parcel: Parcel): DelayTestResult =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<DelayTestResult?> = arrayOfNulls(size)
    }
}
