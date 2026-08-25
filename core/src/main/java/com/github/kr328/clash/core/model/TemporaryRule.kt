package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A user-created rule applied ahead of the selected profile's rules. */
@Serializable
data class TemporaryRule(
    val id: String,
    @SerialName("rule-type") val ruleType: String,
    val payload: String,
    val target: String,
    val label: String,
    @SerialName("created-at") val createdAt: Long,
) : Parcelable {
    fun toRuleString(): String = "$ruleType,$payload,$target"

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<TemporaryRule> {
        override fun createFromParcel(parcel: Parcel): TemporaryRule =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<TemporaryRule?> = arrayOfNulls(size)
    }
}
