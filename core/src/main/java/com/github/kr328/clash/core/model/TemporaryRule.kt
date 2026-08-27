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
        private val ALLOWED_TYPES = setOf("PROCESS-NAME", "DOMAIN-SUFFIX", "SRC-IP-CIDR")

        fun isSafeToApply(rule: TemporaryRule): Boolean {
            val type = rule.ruleType.trim().uppercase()
            val payload = rule.payload.trim()
            val target = rule.target.trim()
            if (type !in ALLOWED_TYPES || payload.isEmpty() || target.isEmpty()) {
                return false
            }
            if (payload.any { it == ',' || it == '\n' || it == '\r' }) {
                return false
            }
            if (target.any { it == ',' || it == '\n' || it == '\r' }) {
                return false
            }
            return true
        }
        override fun createFromParcel(parcel: Parcel): TemporaryRule =
            Parcelizer.decodeFromParcel(serializer(), parcel)

        override fun newArray(size: Int): Array<TemporaryRule?> = arrayOfNulls(size)
    }
}
