package com.pdfreader.app.sync

import android.content.Context
import android.util.Log
import com.google.firebase.database.*

object FirebaseSyncManager {

    private const val TAG  = "FirebaseSyncManager"
    private const val ROOT = "kitaab"

    private val db by lazy { FirebaseDatabase.getInstance() }

    private var pushListener: ValueEventListener? = null
    private var listenerOwner: String?             = null

    /** Unique per install — lets a device ignore the echo of its own push. */
    private var deviceId: String = ""

    // ── NOTIFY after Drive push ───────────────────────────────────────────────

    fun notifyPush(ownerName: String) {
        db.getReference("$ROOT/$ownerName").updateChildren(mapOf(
            "lastPush"       to ServerValue.TIMESTAMP,
            "lastPushDevice" to deviceId
        ))
            .addOnSuccessListener { Log.d(TAG, "Push signal sent ✓") }
            .addOnFailureListener { Log.e(TAG, "Push signal failed: ${it.message}") }
    }

    fun pushWeeklyData(ownerName: String, weeklyJson: String, streakDays: Int, lastReadDay: Long) {
        db.getReference("$ROOT/$ownerName").updateChildren(mapOf(
            "weeklySnapshot" to weeklyJson,
            "streakDays"     to streakDays,
            "lastReadDay"    to lastReadDay
        ))
    }

    fun fetchWeeklyData(
        ownerName: String,
        onResult: (weeklyJson: String?, streakDays: Int, lastReadDay: Long) -> Unit
    ) {
        db.getReference("$ROOT/$ownerName").get()
            .addOnSuccessListener { snap ->
                onResult(
                    snap.child("weeklySnapshot").getValue(String::class.java),
                    snap.child("streakDays").getValue(Long::class.java)?.toInt() ?: 0,
                    snap.child("lastReadDay").getValue(Long::class.java) ?: -1L
                )
            }
            .addOnFailureListener { onResult(null, 0, -1L) }
    }

    // ── LISTEN — app-level only ───────────────────────────────────────────────

    fun startListeningWithDirectPull(
        context: Context,
        ownerName: String,
        lastKnownPush: Long,
        onPullNeeded: (remotePush: Long) -> Unit
    ) {
        deviceId = getDeviceId(context)
        if (listenerOwner == ownerName && pushListener != null) {
            Log.d(TAG, "Listener already active for $ownerName")
            return
        }
        stopListening()

        var knownPush = lastKnownPush
        val ref = db.getReference("$ROOT/$ownerName")
        pushListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remotePush = snapshot.child("lastPush").getValue(Long::class.java) ?: return
                val fromDevice = snapshot.child("lastPushDevice").getValue(String::class.java) ?: ""
                if (remotePush <= knownPush) return
                knownPush = remotePush
                saveLastKnownPush(context, ownerName, remotePush)
                if (fromDevice == deviceId) {
                    Log.d(TAG, "Own push echo — ignored")
                    return
                }
                Log.d(TAG, "New push from another device — syncing")
                onPullNeeded(remotePush)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Listener cancelled: ${error.message}")
            }
        }
        // Listen on the owner node: weekly-data writes also fire, but are ignored because
        // lastPush doesn't change.
        ref.addValueEventListener(pushListener!!)
        listenerOwner = ownerName
        Log.d(TAG, "Listener started for $ownerName (lastKnown=$lastKnownPush)")
    }

    /** Kept so old call sites compile; the app-level listener handles everything. */
    fun startListening(context: Context, ownerName: String, lastKnownPush: Long) {
        Log.d(TAG, "startListening called (handled at app level)")
    }

    fun stopListening() {
        pushListener?.let { l ->
            listenerOwner?.let { owner -> db.getReference("$ROOT/$owner").removeEventListener(l) }
        }
        pushListener  = null
        listenerOwner = null
        Log.d(TAG, "Listener stopped")
    }

    // ── SharedPreferences ─────────────────────────────────────────────────────

    fun getLastKnownPush(context: Context, ownerName: String): Long =
        context.getSharedPreferences("firebase_sync", Context.MODE_PRIVATE)
            .getLong("last_push_$ownerName", 0L)

    fun saveLastKnownPush(context: Context, ownerName: String, timestamp: Long) =
        context.getSharedPreferences("firebase_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_push_$ownerName", timestamp).apply()

    private fun getDeviceId(context: Context): String {
        val sp = context.getSharedPreferences("firebase_sync", Context.MODE_PRIVATE)
        return sp.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            sp.edit().putString("device_id", it).apply()
        }
    }
}
