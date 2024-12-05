package com.shounakmulay.telephony.notifications

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shounakmulay.telephony.sms.IncomingNotificationReceiver
import com.shounakmulay.telephony.utils.Constants.MESSAGE_BODY
import com.shounakmulay.telephony.utils.Constants.ORIGINATING_ADDRESS
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFERENCES_NAME
import com.shounakmulay.telephony.utils.Constants.STATUS
import com.shounakmulay.telephony.utils.Constants.SUBJECT
import com.shounakmulay.telephony.utils.Constants.TIMESTAMP
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.ObjectInputStream


class NLService : NotificationListenerService() {

    private val TAG = this.javaClass.simpleName
    private lateinit var nlServiceReceiver: IncomingNotificationReceiver

    override fun onCreate() {
        super.onCreate()
        nlServiceReceiver = IncomingNotificationReceiver()
        val filter = IntentFilter().apply {
            addAction("com.shounakmulay.telephony.NOTIFICATION_LISTENER_SERVICE")
        }
//        registerReceiver(nlServiceReceiver, filter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nlServiceReceiver, filter, RECEIVER_EXPORTED)
        }else {
            registerReceiver(nlServiceReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IsolateHolderService::WAKE_LOCK").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }

            // create a channel for notification
            val channel = NotificationChannel("notifications_listener_channel", "Notifications Listener", NotificationManager.IMPORTANCE_HIGH)
            val imageId = resources.getIdentifier("ic_launcher", "mipmap", packageName)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, "notifications_listener_channel")
                .setContentTitle("Bliss")
                .setContentText("Notifications Listener Service")
                .setShowWhen(false)
//            .setSubText()
                .setSmallIcon(imageId)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()

            Log.d(TAG, "promote the service to foreground")
            startForeground(113, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(nlServiceReceiver)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IsolateHolderService::WAKE_LOCK").apply {
                    if (isHeld) release()
                }
            }
        }
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            stopForeground(STOP_FOREGROUND_REMOVE)
//        } else {
//            stopForeground(true)
//        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "TASK REMOVED")

        val service = PendingIntent.getService(
            applicationContext,
            1001,
            Intent(applicationContext, NLService::class.java),
            PendingIntent.FLAG_ONE_SHOT
        )

        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager[AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000] = service
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Log.i(TAG, "**********  onNotificationPosted")
        val extras: Bundle = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE, "")
        val text = extras.getCharSequence(Notification.EXTRA_TEXT, "")

        val address = sbn.packageName
        val isLocalOnly = (sbn.notification.flags and Notification.FLAG_LOCAL_ONLY) != 0 || sbn.id == 113

        Log.i(TAG, "ID: ${sbn.id}\nTitle: $title\nText: $text\nPackage: $address\nLocal Only: ${isLocalOnly}\nExtras: ${extras.toString()}\nNotification: ${sbn.notification.toString()}")

        if(!isLocalOnly && !(text.isNullOrEmpty() && title.isNullOrEmpty())) {

            val notificationData = HashMap<String, Any>().apply {
                put(ORIGINATING_ADDRESS, address ?: "")
                put(MESSAGE_BODY, text ?: "")
                put(TIMESTAMP, "${sbn.postTime}")
                put(SUBJECT, title)
                put(STATUS, "-1")
                put("PUSH", "1")
                put("EXTRA", extras.toString())
                put("NOTIFICATION", sbn.notification.toString())
            }

            var sharedPreferences = this
                .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)

            var listEncoded =
                sharedPreferences.getString("flutter.SHOULD_PARSE_PUSH_FROM", "")
            var onlyAuthorized = sharedPreferences.getBoolean("flutter.onlyAuthorized", true)
            var shouldParseFrom = if (listEncoded.isNullOrEmpty()) listOf() else decodeList(
                listEncoded.substring(40))
            Log.i(TAG, "SHOULD PARSE FROM: $shouldParseFrom")
            if(onlyAuthorized) {
                if(shouldParseFrom.contains(sbn.packageName)) {
                    cancelNotification(sbn.key)

                    val intent = Intent("com.shounakmulay.telephony.NOTIFICATION_LISTENER_SERVICE").apply {
                        putExtra("notification_data", notificationData)
                    }

                    sendBroadcast(intent)
                }
            } else {
                cancelNotification(sbn.key)

                val intent = Intent("com.shounakmulay.telephony.NOTIFICATION_LISTENER_SERVICE").apply {
                    putExtra("notification_data", notificationData)
                }

                sendBroadcast(intent)
            }
        }
    }

    @Throws(IOException::class)
    private fun decodeList(encodedList: String): List<String> {
        var stream: ObjectInputStream? = null
        try {
            stream = ObjectInputStream(ByteArrayInputStream(Base64.decode(encodedList, 0)))
            return stream.readObject() as List<String>
        } catch (e: ClassNotFoundException) {
            throw IOException(e)
        } finally {
            stream?.close()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        Log.i(TAG, "********** onNotificationRemoved")
        Log.i(TAG, "ID: ${sbn.id}\t${sbn.notification.tickerText}\t${sbn.packageName}")

//        val intent = Intent("com.shounakmulay.telephony.NOTIFICATION_LISTENER_SERVICE").apply {
//            putExtra("notification_event", "onNotificationRemoved: ${sbn.packageName}\n")
//        }
//        sendBroadcast(intent)
    }
}