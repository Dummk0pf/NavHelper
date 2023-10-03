/* -*- mode: C++; c-basic-offset: 4; indent-tabs-mode: nil; -*- */
/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Copyright (c) 2020 Marco Trevisan <mail@trevi.me>
 */

package me.trevi.navparser.service

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import me.trevi.navparser.BuildConfig
import me.trevi.navparser.lib.*
import me.trevi.navparser.service.BluetoothDataService
import me.trevi.navparser.service.BluetoothDataService.LocalBinder
import timber.log.Timber as tLog


private const val NOTIFICATIONS_THRESHOLD : Long = 500 // Ignore notifications coming earlier, in ms

open class NavigationListener : NotificationListenerService() {
    private var mNotificationParserCoroutine: Job? = null;
    private lateinit var mLastNotification: StatusBarNotification
    private var mCurrentNotification: NavigationNotification? = null
    private var mNotificationsThreshold: Long = NOTIFICATIONS_THRESHOLD
    private var mEnabled = false

    //For Bluetooth
    var mBounded: Boolean = false
    var count: Int = 0;
    var mServer: BluetoothDataService? = null

    protected var enabled: Boolean
        get() = mEnabled
        set(value) {
            if (value == mEnabled)
                return
            if (value.also { mEnabled = it })
                checkActiveNotifications()
            else
                mCurrentNotification = null

            tLog.v("Navigation listener enabled: $mEnabled")
        }

    protected var notificationsThreshold: Long
        get() = mNotificationsThreshold
        set(value) {
            mNotificationsThreshold = if (value < 0) NOTIFICATIONS_THRESHOLD else value
            mNotificationParserCoroutine?.cancel()
            checkActiveNotifications()
        }

    protected val currentNotification: NavigationNotification?
        get() = mCurrentNotification

    override fun onCreate() {
        super.onCreate()

        checkBluetoothAccess()
//        val mIntent = Intent(this, BluetoothDataService::class.java)
//        bindService(mIntent, mConnection, BIND_AUTO_CREATE)

        Log.d("BLUETOOTHTAG", "NavigationListener On Create")

        if (BuildConfig.DEBUG) {
            tLog.plant(tLog.DebugTree())
        }

        if (haveNotificationsAccess()) {
            tLog.d("Notifications access granted to ${this.javaClass.name}!")
        } else {
            tLog.e("No notification access granted to ${this.javaClass.name}!")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("BLUETOOTHTAG","ONSTARTCOMMAND")
        checkBluetoothAccess()

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onListenerConnected() {
        super.onListenerConnected();
        Log.d("BLUETOOTHTAG", "NavigationListener On Listener Connected")

        checkActiveNotifications()
    }

    private fun checkActiveNotifications() {
//        Log.d("NOTIFICATIONTAG","NavigationListener checkActiveNotifications")
        if (Build.VERSION.SDK_INT < 23 || !haveNotificationsAccess())
            return

        try {
            tLog.d("Checking for active Navigation notifications")
            this.activeNotifications.forEach { sbn -> onNotificationPosted(sbn) }
        } catch (e: Throwable) {
            tLog.e("Failed to check for active notifications: $e")
        }
    }

    private fun isGoogleMapsNotification(sbn: StatusBarNotification?): Boolean {
//        Log.d("NOTIFICATIONTAG","NavigationListener isGoogleMapsNotification")
        if (!mEnabled)
            return false

        if (!sbn!!.isOngoing || GMAPS_PACKAGE !in sbn.packageName)
            return false

        return (sbn.id == 1)
    }

    fun haveNotificationsAccess(): Boolean {
        Log.d("BLUETOOTHTAG", "NavigationListener haveNotificationAccess")
        Settings.Secure.getString(
            this.contentResolver, "enabled_notification_listeners"
        ).also {
            tLog.v("Checking if ${this::class.qualifiedName} has notification access")
            return this::class.qualifiedName.toString() in it
        }
    }

    protected open fun onNavigationNotificationAdded(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationUpdated(navNotification: NavigationNotification) {
    }

    protected open fun onNavigationNotificationRemoved(navNotification: NavigationNotification) {
    }

    private fun handleGoogleNotification(sbn: StatusBarNotification) {
//        Log.d("NOTIFICATIONTAG","NavigationListener handleGoogleNotification")
        mLastNotification = sbn;

        if (mNotificationParserCoroutine != null && mNotificationParserCoroutine!!.isActive)
            return

        mNotificationParserCoroutine = GlobalScope.launch(Dispatchers.Main) {
            if (mCurrentNotification != null)
                delay(notificationsThreshold)

            val worker = GlobalScope.async(Dispatchers.Default) {
                return@async GMapsNotification(
                    this@NavigationListener.applicationContext,
                    mLastNotification
                );
            }

            try {
                val mapNotification = worker.await()
                val lastNotification = mCurrentNotification
                val updated: Boolean



                if (lastNotification == null) {
                    BluetoothDataService.navData = null.toString()
                    onNavigationNotificationAdded(mapNotification)
                    updated = true
                } else {
                    if(mapNotification == null)
                        BluetoothDataService.navData = "null"
                    else
                        BluetoothDataService.navData = "${lastNotification.navigationData.toString()}$$"
                    updated = lastNotification.navigationData != mapNotification.navigationData
                    tLog.v("Notification is different than previous: $updated")
                }

                if (updated) {
                    mCurrentNotification = mapNotification

                    onNavigationNotificationUpdated(mCurrentNotification!!);
                }
            } catch (error: Exception) {
                if (!mNotificationParserCoroutine!!.isCancelled)
                    tLog.e("Got an error while parsing: $error");
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
//        Log.d("NOTIFICATIONTAG","onNotificationPosted")
        tLog.v("Got notification from ${sbn?.packageName}")

        if (sbn != null && isGoogleMapsNotification(sbn))
            handleGoogleNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isGoogleMapsNotification(sbn)) {
            tLog.d("Notification removed ${sbn}, ${sbn.hashCode()}")
            mNotificationParserCoroutine?.cancel();

            onNavigationNotificationRemoved(
                if (mCurrentNotification != null) mCurrentNotification!!
                else NavigationNotification(applicationContext, sbn)
            )

            mCurrentNotification = null;
        }
    }

    fun startNavigation(
        destination: String,
        mode: NavigationMode = NavigationMode.DRIVING,
        avoid: NavigationAvoid = emptySet()
    ) {
        val builder = Uri.Builder()
        builder.scheme("google.navigation")
            .appendQueryParameter("q", destination)

        builder.appendQueryParameter(
            "mode",
            when (mode) {
                NavigationMode.DRIVING -> "d"
                NavigationMode.BICYCLING -> "b"
                NavigationMode.TWO_WHEELER -> "l"
                NavigationMode.WALKING -> "w"
            }
        )

        if (avoid.isNotEmpty()) {
            var avoidString = ""
            avoid.forEach {
                avoidString += when (it) {
                    NavigationAvoidElement.HIGHWAYS -> 'h'
                    NavigationAvoidElement.TOLLS -> 't'
                    NavigationAvoidElement.FERRIES -> 'f'
                }
            }
            builder.appendQueryParameter("avoid", avoidString)
        }

        val navigationUri = builder.build().toString()
        tLog.i("Navigating to URI: $navigationUri")
        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(navigationUri))
        mapIntent.setPackage(GMAPS_PACKAGE)
        mapIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(mapIntent)
    }

    // For bluetooth
    private fun checkBluetoothAccess() {
        Log.d("BLUETOOTHTAG", "checkBluetoothAccess")
        startService(bluetoothServiceIntent())
    }

    private fun bluetoothServiceIntent(): Intent {
        Log.d("BLUETOOTHTAG", "bluetoothServiceIntent")
        val intent = Intent(this, BluetoothDataService::class.java)
//        val pack = "Hello"
//        intent.putExtra("NAVDATA", pack)
        return intent
    }

//    var mConnection: ServiceConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName) {
////            Toast.makeText(this@NavigationListener, "Service is disconnected", 1000).show()
//            mBounded = false
//            mServer = null
//        }
//
//        override fun onServiceConnected(name: ComponentName, service: IBinder) {
////            Toast.makeText(this@NavigationListener, "Service is connected", 1000).show()
//            mBounded = true
//            val mLocalBinder: LocalBinder = BluetoothDataService().LocalBinder()
//            mServer = mLocalBinder.getServerInstance()
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        if (mBounded) {
//            unbindService(mConnection)
//            mBounded = false
//        }
//    }

//    override fun onStop() {
//        super.onStop()
//        if (mBounded) {
//            unbindService(mConnection)
//            mBounded = false
//         }
//    }

}
