package com.leapauto.app

import android.os.Bundle
import android.os.SystemClock

internal object OperationPin {
    fun isValid(value: String): Boolean = value.length == 4 && value.all { it in '0'..'9' }

    fun filterInput(value: String): String = value.filter { it in '0'..'9' }.take(4)
}

/** Carries the requested action and session identity without carrying its PIN. */
internal data class ExternalControlRequest(
    val command: String,
    val vin: String,
    val accountId: String,
    val generation: Long,
    val createdAt: Long
) {
    fun isCurrent(session: Session, now: Long = SystemClock.elapsedRealtime()): Boolean =
        vin.isNotBlank() && vin == session.selectedVin &&
            accountId.isNotBlank() && accountId == session.oldAuth?.accountId &&
            generation == session.generation && session.newAuth != null &&
            now - createdAt in 0..EXPIRY_MS

    fun toBundle() = Bundle().apply {
        putString("command", command)
        putString("vin", vin)
        putString("account_id", accountId)
        putLong("generation", generation)
        putLong("created_at", createdAt)
    }

    companion object {
        const val EXPIRY_MS = 120_000L

        fun create(session: Session, command: String) = ExternalControlRequest(
            command, session.selectedVin, session.oldAuth?.accountId.orEmpty(),
            session.generation, SystemClock.elapsedRealtime()
        )

        fun fromBundle(bundle: Bundle): ExternalControlRequest? {
            val command = bundle.getString("command") ?: return null
            val vin = bundle.getString("vin") ?: return null
            val accountId = bundle.getString("account_id") ?: return null
            return ExternalControlRequest(
                command, vin, accountId, bundle.getLong("generation", -1),
                bundle.getLong("created_at", -1)
            )
        }
    }
}
