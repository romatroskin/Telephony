package com.shounakmulay.telephony

import android.content.Context
import androidx.annotation.NonNull
import com.shounakmulay.telephony.sms.IncomingNotificationReceiver
import com.shounakmulay.telephony.sms.IncomingSmsHandler
import com.shounakmulay.telephony.utils.Constants.CHANNEL_SMS
import com.shounakmulay.telephony.sms.IncomingSmsReceiver
import com.shounakmulay.telephony.sms.SmsController
import com.shounakmulay.telephony.sms.SmsMethodCallHandler
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFERENCES_NAME
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_BACKGROUND_SETUP_HANDLE
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*


class TelephonyPlugin : FlutterPlugin, ActivityAware {

  private lateinit var smsChannel: MethodChannel

  private lateinit var smsMethodCallHandler: SmsMethodCallHandler

  private lateinit var smsController: SmsController

  private lateinit var binaryMessenger: BinaryMessenger

  private lateinit var permissionsController: PermissionsController

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    if (!this::binaryMessenger.isInitialized) {
      binaryMessenger = flutterPluginBinding.binaryMessenger
    }

    setupPlugin(flutterPluginBinding.applicationContext, binaryMessenger)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    tearDownPlugin()
  }

  override fun onDetachedFromActivity() {
    tearDownPlugin()
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    IncomingSmsReceiver.foregroundSmsChannel = smsChannel
//    IncomingNotificationReceiver.foregroundSmsChannel = smsChannel
    smsMethodCallHandler.setActivity(binding.activity)
    binding.addRequestPermissionsResultListener(smsMethodCallHandler)
    binding.addActivityResultListener(smsMethodCallHandler)

//    IncomingSmsHandler.apply {
//      if (!isIsolateRunning.get()) {
//        initialize(binding.activity.applicationContext)
//        val preferences =
//          binding.activity.applicationContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
//        val backgroundCallbackHandle =
//          preferences.getLong(SHARED_PREFS_BACKGROUND_SETUP_HANDLE, 0)
//        startBackgroundIsolate(binding.activity.applicationContext, backgroundCallbackHandle)
//      }
//    }
  }

  override fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }

  private fun setupPlugin(context: Context, messenger: BinaryMessenger) {
    smsController = SmsController(context)
    permissionsController = PermissionsController(context)
    smsMethodCallHandler = SmsMethodCallHandler(context, smsController, permissionsController)

    smsChannel = MethodChannel(messenger, CHANNEL_SMS)
    smsChannel.setMethodCallHandler(smsMethodCallHandler)
    smsMethodCallHandler.setForegroundChannel(smsChannel)
  }

  private fun tearDownPlugin() {
//     IncomingSmsReceiver.foregroundSmsChannel = null
//     smsChannel.setMethodCallHandler(null)
  }

}
