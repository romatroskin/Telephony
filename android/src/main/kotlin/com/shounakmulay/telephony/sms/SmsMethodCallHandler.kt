package com.shounakmulay.telephony.sms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import android.provider.Telephony
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.shounakmulay.telephony.PermissionsController
import com.shounakmulay.telephony.sms.IncomingSmsHandler.startBackgroundIsolate
import com.shounakmulay.telephony.utils.ActionType
import com.shounakmulay.telephony.utils.Constants
import com.shounakmulay.telephony.utils.Constants.ADDRESS
import com.shounakmulay.telephony.utils.Constants.BACKGROUND_HANDLE
import com.shounakmulay.telephony.utils.Constants.CALL_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.DEFAULT_CONVERSATION_PROJECTION
import com.shounakmulay.telephony.utils.Constants.DEFAULT_SMS_PROJECTION
import com.shounakmulay.telephony.utils.Constants.FAILED_FETCH
import com.shounakmulay.telephony.utils.Constants.GET_STATUS_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.ILLEGAL_ARGUMENT
import com.shounakmulay.telephony.utils.Constants.LISTEN_STATUS
import com.shounakmulay.telephony.utils.Constants.MESSAGE_BODY
import com.shounakmulay.telephony.utils.Constants.PERMISSION_DENIED
import com.shounakmulay.telephony.utils.Constants.PERMISSION_DENIED_MESSAGE
import com.shounakmulay.telephony.utils.Constants.PERMISSION_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.PHONE_NUMBER
import com.shounakmulay.telephony.utils.Constants.PROJECTION
import com.shounakmulay.telephony.utils.Constants.SELECTION
import com.shounakmulay.telephony.utils.Constants.SELECTION_ARGS
import com.shounakmulay.telephony.utils.Constants.SETUP_HANDLE
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFERENCES_NAME
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_BACKGROUND_SETUP_HANDLE
import com.shounakmulay.telephony.utils.Constants.SHARED_PREFS_DISABLE_BACKGROUND_EXE
import com.shounakmulay.telephony.utils.Constants.SMS_BACKGROUND_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_DELIVERED
import com.shounakmulay.telephony.utils.Constants.SMS_QUERY_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_SEND_REQUEST_CODE
import com.shounakmulay.telephony.utils.Constants.SMS_SENT
import com.shounakmulay.telephony.utils.Constants.SORT_ORDER
import com.shounakmulay.telephony.utils.Constants.SUBSCRIPTION_ID
import com.shounakmulay.telephony.utils.Constants.WORKMANAGER_HANDLE
import com.shounakmulay.telephony.utils.Constants.WRONG_METHOD_TYPE
import com.shounakmulay.telephony.utils.ContentUri
import com.shounakmulay.telephony.utils.SmsAction
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.util.Random
import java.util.concurrent.TimeUnit


class SmsMethodCallHandler(
    private val context: Context,
    private val smsController: SmsController,
    private val permissionsController: PermissionsController
) : PluginRegistry.RequestPermissionsResultListener,
    PluginRegistry.ActivityResultListener,
    MethodChannel.MethodCallHandler,
    BroadcastReceiver() {

  private lateinit var result: MethodChannel.Result
  private lateinit var action: SmsAction
  private lateinit var foregroundChannel: MethodChannel
  private lateinit var activity: Activity

  private var projection: List<String>? = null
  private var selection: String? = null
  private var selectionArgs: List<String>? = null
  private var sortOrder: String? = null

  private lateinit var messageBody: String
  private lateinit var address: String
  private var subId: Int = -1
  private var listenStatus: Boolean = false

  private var setupHandle: Long = -1
  private var backgroundHandle: Long = -1
  private var workManagerHandle: Long = -1

  private lateinit var phoneNumber: String

  private lateinit var uniqueTaskName: String
  private lateinit var taskName: String
  private lateinit var inputData: String

  private var requestCode: Int = -1

  companion object {
        // The requested role.
        @RequiresApi(Build.VERSION_CODES.Q)
        const val ROLE = RoleManager.ROLE_SMS

        const val PAYLOAD_KEY = "com.bliss.workmanager.INPUT_DATA"
        const val DART_TASK_KEY = "com.bliss.workmanager.DART_TASK"
    }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    action = SmsAction.fromMethod(call.method)

    if (action == SmsAction.NO_SUCH_METHOD) {
      result.notImplemented()
      return
    }

    when (action.toActionType()) {
      ActionType.GET_SMS -> {
        this.result = result
        projection = call.argument(PROJECTION)
        selection = call.argument(SELECTION)
        selectionArgs = call.argument(SELECTION_ARGS)
        sortOrder = call.argument(SORT_ORDER)

        handleMethod(action, SMS_QUERY_REQUEST_CODE)
      }
      ActionType.SEND_SMS -> {
        this.result = result
        if (call.hasArgument(MESSAGE_BODY)
            && call.hasArgument(ADDRESS)) {
          val messageBody = call.argument<String>(MESSAGE_BODY)
          val address = call.argument<String>(ADDRESS)
          var subId: Int? = call.argument<Int>(SUBSCRIPTION_ID)
          if (messageBody.isNullOrBlank() || address.isNullOrBlank()) {
            result.error(ILLEGAL_ARGUMENT, Constants.MESSAGE_OR_ADDRESS_CANNOT_BE_NULL, null)
            return
          }

          this.messageBody = messageBody
          this.address = address
          if(subId != null){
            this.subId = subId
          }

          listenStatus = call.argument(LISTEN_STATUS) ?: false
        }
        handleMethod(action, SMS_SEND_REQUEST_CODE)
      }
      ActionType.BACKGROUND -> {
        this.result = result
        if (call.hasArgument(SETUP_HANDLE)
          && call.hasArgument(BACKGROUND_HANDLE)
        ) {
          val setupHandle = call.argument<Long>(SETUP_HANDLE)
          val backgroundHandle = call.argument<Long>(BACKGROUND_HANDLE)
          val workManagerHandle = call.argument<Long>(WORKMANAGER_HANDLE)
          if (setupHandle == null || backgroundHandle == null || workManagerHandle == null) {
            result.error(ILLEGAL_ARGUMENT, "Setup handle or background handle missing", null)
            return
          }

          this.setupHandle = setupHandle
          this.backgroundHandle = backgroundHandle
          this.workManagerHandle = workManagerHandle
        } else {
          if(call.hasArgument("uniqueName") && call.hasArgument("taskName")) {
            val uniqTaskName = call.argument<String>("uniqueName")
            val taskName = call.argument<String>("taskName")
            var inputData = call.argument<String>("inputData")

            if (uniqTaskName == null || taskName == null || inputData == null) {
              result.error(ILLEGAL_ARGUMENT, "Task identifiers and data missing", null)
              return
            }

            this.uniqueTaskName = uniqTaskName
            this.taskName = taskName
            this.inputData = inputData
            Log.i("Work Manager Worker",  "--- $uniqTaskName <-> $taskName$inputData ---")
          }
        }
        handleMethod(action, SMS_BACKGROUND_REQUEST_CODE)
      }
      ActionType.GET -> {
        this.result = result
        handleMethod(action, GET_STATUS_REQUEST_CODE)
      }
      ActionType.PERMISSION -> {
        this.result = result
        handleMethod(action, PERMISSION_REQUEST_CODE)
      }
      ActionType.CALL -> {
        this.result = result
        if (call.hasArgument(PHONE_NUMBER)) {
          val phoneNumber = call.argument<String>(PHONE_NUMBER)

          if (!phoneNumber.isNullOrBlank()) {
            this.phoneNumber = phoneNumber
          }

          handleMethod(action, CALL_REQUEST_CODE)
        }
      }
      ActionType.CANCEL_WORKMANAGER_TASKS -> {
//        this.result = result
        WorkManager.getInstance(context).cancelAllWork()
        result.success(true)
      }
      ActionType.NOTIFICATION_PERMISSION -> {
//        this.result = result
        result.success(smsController.getNotificationPermission())
      }
      ActionType.OPEN_SETTINGS -> {
        context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
      ActionType.CHANGE_DEFAULT -> {
        this.result = result
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager: RoleManager = context.applicationContext.getSystemService(RoleManager::class.java)
            // check if the app is having permission to be as default SMS app
            val isRoleAvailable = roleManager.isRoleAvailable(ROLE)
            if (isRoleAvailable) {
                // check whether your app is already holding the default SMS app role.
                val isRoleHeld = roleManager.isRoleHeld(ROLE)
                if (!isRoleHeld) {
                    // intentLauncher.launch(roleManager.createRequestRoleIntent(role))
                    val intent = roleManager.createRequestRoleIntent(ROLE)
                    activity.startActivityForResult(intent, 310010013)
                } else {
                    // Request permission for SMS
                }
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, "com.bliss.parser.app")
            activity.startActivityForResult(intent, 310010013)
        }
      }
    }
  }

  override fun onActivityResult(code: Int, resultCode: Int, data: Intent?): Boolean {
        if (code == 310010013) {
          if (resultCode == Activity.RESULT_OK) {
            result.success(true)
          } else {
            result.success(false)
          }
          return true
        }
        return false
      }
    

  /**
   * Called by [handleMethod] after checking the permissions.
   *
   * #####
   *
   * If permission was not previously granted, [handleMethod] will request the user for permission
   *
   * Once user grants the permission this method will be executed.
   *
   * #####
   */
  private fun execute(smsAction: SmsAction) {
    try {
      when (smsAction.toActionType()) {
        ActionType.GET_SMS -> handleGetSmsActions(smsAction)
        ActionType.SEND_SMS -> handleSendSmsActions(smsAction)
        ActionType.BACKGROUND -> handleBackgroundActions(smsAction)
        ActionType.GET -> handleGetActions(smsAction)
        ActionType.PERMISSION -> result.success(true)
        ActionType.CALL -> handleCallActions(smsAction)
        ActionType.NOTIFICATION_PERMISSION -> {}
        ActionType.CHANGE_DEFAULT -> result.success(true)
        ActionType.OPEN_SETTINGS -> result.success(true)
        ActionType.CANCEL_WORKMANAGER_TASKS -> result.success(true)
      }
    } catch (e: IllegalArgumentException) {
      result.error(ILLEGAL_ARGUMENT, WRONG_METHOD_TYPE, null)
    } catch (e: RuntimeException) {
      result.error(FAILED_FETCH, e.message, null)
    }
  }

  private fun handleGetSmsActions(smsAction: SmsAction) {
    if (projection == null) {
      projection = if (smsAction == SmsAction.GET_CONVERSATIONS) DEFAULT_CONVERSATION_PROJECTION else DEFAULT_SMS_PROJECTION
    }
    val contentUri = when (smsAction) {
      SmsAction.GET_INBOX -> ContentUri.INBOX
      SmsAction.GET_SENT -> ContentUri.SENT
      SmsAction.GET_DRAFT -> ContentUri.DRAFT
      SmsAction.GET_CONVERSATIONS -> ContentUri.CONVERSATIONS
      else -> throw IllegalArgumentException()
    }
    val messages = smsController.getMessages(contentUri, projection!!, selection, selectionArgs, sortOrder)
    result.success(messages)
  }

  private fun handleSendSmsActions(smsAction: SmsAction) {
    if (listenStatus) {
      val intentFilter = IntentFilter().apply {
        addAction(Constants.ACTION_SMS_SENT)
        addAction(Constants.ACTION_SMS_DELIVERED)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.applicationContext.registerReceiver(this, intentFilter, RECEIVER_EXPORTED)
      }else {
        context.applicationContext.registerReceiver(this, intentFilter)
      }
    }
    when (smsAction) {
      SmsAction.SEND_SMS -> smsController.sendSms(address, messageBody, listenStatus, subId)
      SmsAction.SEND_MULTIPART_SMS -> smsController.sendMultipartSms(address, messageBody, listenStatus, subId)
      SmsAction.SEND_SMS_INTENT -> smsController.sendSmsIntent(address, messageBody)
      else -> throw IllegalArgumentException()
    }
    result.success(null)
  }

  private fun handleBackgroundActions(smsAction: SmsAction) {
    when (smsAction) {
      SmsAction.START_BACKGROUND_SERVICE -> {
        val preferences =
          context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(SHARED_PREFS_DISABLE_BACKGROUND_EXE, false).apply()
        IncomingSmsHandler.setBackgroundSetupHandle(context, setupHandle)
        IncomingSmsHandler.setBackgroundMessageHandle(context, backgroundHandle)
        IncomingSmsHandler.setWorkManagerHandle(context, workManagerHandle)

        ContextHolder.applicationContext = context
        IncomingSmsHandler.apply {
          initialize(context)

          val backgroundCallbackHandle =
            preferences.getLong(SHARED_PREFS_BACKGROUND_SETUP_HANDLE, 0)
          startBackgroundIsolate(context, backgroundCallbackHandle)
        }
      }
      SmsAction.BACKGROUND_SERVICE_INITIALIZED -> {
        IncomingSmsHandler.onChannelInitialized(context.applicationContext)
      }
      SmsAction.DISABLE_BACKGROUND_SERVICE -> {
        val preferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit().putBoolean(SHARED_PREFS_DISABLE_BACKGROUND_EXE, true).apply()
      }
      SmsAction.RUN_WORKMANAGER_TASK -> {
        val oneOffTaskRequest = OneTimeWorkRequest.Builder(WorkManagerWorker::class.java)
          .setInputData(buildTaskInputData(taskName, inputData))
          .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
//          .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
          .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueTaskName, ExistingWorkPolicy.REPLACE, oneOffTaskRequest)
      }
      else -> throw IllegalArgumentException()
    }
  }

  private fun buildTaskInputData(
    dartTask: String,
    payload: String?,
  ): Data {
    return Data.Builder()
      .putString(DART_TASK_KEY, dartTask)
      .apply {
        payload?.let {
          putString(PAYLOAD_KEY, payload)
        }
      }
      .build()
  }

  @SuppressLint("MissingPermission")
  private fun handleGetActions(smsAction: SmsAction) {
    smsController.apply {
      val value: Any = when (smsAction) {
        SmsAction.IS_SMS_CAPABLE -> isSmsCapable()
        SmsAction.GET_CELLULAR_DATA_STATE -> getCellularDataState()
        SmsAction.GET_CALL_STATE -> getCallState()
        SmsAction.GET_DATA_ACTIVITY -> getDataActivity()
        SmsAction.GET_NETWORK_OPERATOR -> getNetworkOperator()
        SmsAction.GET_NETWORK_OPERATOR_NAME -> getNetworkOperatorName()
        SmsAction.GET_DATA_NETWORK_TYPE -> getDataNetworkType()
        SmsAction.GET_PHONE_TYPE -> getPhoneType()
        SmsAction.GET_SIM_OPERATOR -> getSimOperator()
        SmsAction.GET_SIM_OPERATOR_NAME -> getSimOperatorName()
        SmsAction.GET_SIM_STATE -> getSimState()
        SmsAction.IS_NETWORK_ROAMING -> isNetworkRoaming()
        SmsAction.GET_SIGNAL_STRENGTH -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSignalStrength()
                ?: result.error("SERVICE_STATE_NULL", "Error getting service state", null)

          } else {
            result.error("INCORRECT_SDK_VERSION", "getServiceState() can only be called on Android Q and above", null)
          }
        }
        SmsAction.GET_SERVICE_STATE -> {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getServiceState()
                ?: result.error("SERVICE_STATE_NULL", "Error getting service state", null)
          } else {
            result.error("INCORRECT_SDK_VERSION", "getServiceState() can only be called on Android O and above", null)
          }
        }
        SmsAction.GET_DEFAULT_SMS_APP -> getDefaultSmsPackage()
        else -> throw IllegalArgumentException()
      }
      result.success(value)
    }
  }

  @SuppressLint("MissingPermission")
  private fun handleCallActions(smsAction: SmsAction) {
    when (smsAction) {
      SmsAction.OPEN_DIALER -> smsController.openDialer(phoneNumber)
      SmsAction.DIAL_PHONE_NUMBER -> smsController.dialPhoneNumber(phoneNumber)
      else -> throw IllegalArgumentException()
    }
  }


  /**
   * Calls the [execute] method after checking if the necessary permissions are granted.
   *
   * If not granted then it will request the permission from the user.
   */
  private fun handleMethod(smsAction: SmsAction, requestCode: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkOrRequestPermission(smsAction, requestCode)) {
      execute(smsAction)
    }
  }

  /**
   * Check and request if necessary for all the SMS permissions listed in the manifest
   */
  @RequiresApi(Build.VERSION_CODES.M)
  fun checkOrRequestPermission(smsAction: SmsAction, requestCode: Int): Boolean {
    this.action = smsAction
    this.requestCode = requestCode
    when (smsAction) {
      SmsAction.GET_INBOX,
      SmsAction.GET_SENT,
      SmsAction.GET_DRAFT,
      SmsAction.GET_CONVERSATIONS,
      SmsAction.SEND_SMS,
      SmsAction.SEND_MULTIPART_SMS,
      SmsAction.SEND_SMS_INTENT,
      SmsAction.START_BACKGROUND_SERVICE,
      SmsAction.BACKGROUND_SERVICE_INITIALIZED,
      SmsAction.DISABLE_BACKGROUND_SERVICE,
      SmsAction.RUN_WORKMANAGER_TASK,
      SmsAction.REQUEST_SMS_PERMISSIONS -> {
        val permissions = permissionsController.getSmsPermissions()
        return checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.GET_DATA_NETWORK_TYPE,
      SmsAction.OPEN_DIALER,
      SmsAction.DIAL_PHONE_NUMBER,
      SmsAction.REQUEST_PHONE_PERMISSIONS -> {
        val permissions = permissionsController.getPhonePermissions()
        return checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.GET_SERVICE_STATE -> {
        val permissions = permissionsController.getServiceStatePermissions()
        return checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.REQUEST_PHONE_AND_SMS_PERMISSIONS -> {
        val permissions = listOf(permissionsController.getSmsPermissions(), permissionsController.getPhonePermissions()).flatten()
        return checkOrRequestPermission(permissions, requestCode)
      }
      SmsAction.IS_SMS_CAPABLE,
      SmsAction.GET_CELLULAR_DATA_STATE,
      SmsAction.GET_CALL_STATE,
      SmsAction.GET_DATA_ACTIVITY,
      SmsAction.GET_NETWORK_OPERATOR,
      SmsAction.GET_NETWORK_OPERATOR_NAME,
      SmsAction.GET_PHONE_TYPE,
      SmsAction.GET_SIM_OPERATOR,
      SmsAction.GET_SIM_OPERATOR_NAME,
      SmsAction.GET_SIM_STATE,
      SmsAction.IS_NETWORK_ROAMING,
      SmsAction.GET_SIGNAL_STRENGTH,
      SmsAction.SET_AS_DEFAULT_SMS_APP,
      SmsAction.GET_DEFAULT_SMS_APP,
      SmsAction.GET_NOTIFICATION_PERMISSION,
      SmsAction.SET_NOTIFICATION_PERMISSION,
      SmsAction.CANCEL_WORKMANAGER_TASKS,
      SmsAction.NO_SUCH_METHOD -> return true
    }
  }

  fun setActivity(activity: Activity) {
    this.activity = activity
  }

  @RequiresApi(Build.VERSION_CODES.M)
  private fun checkOrRequestPermission(permissions: List<String>, requestCode: Int): Boolean {
    permissionsController.apply {
      
      if (!::activity.isInitialized) {
        return hasRequiredPermissions(permissions)
      }
      
      if (!hasRequiredPermissions(permissions)) {
        requestPermissions(activity, permissions, requestCode)
        return false
      }
      return true
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {

    permissionsController.isRequestingPermission = false
    val deniedPermissions = mutableListOf<String>()

    if (requestCode != this.requestCode || !this::action.isInitialized) {
      return false
    }

    val allPermissionGranted = grantResults.foldIndexed(true) { i, acc, result ->
      if (result == PackageManager.PERMISSION_DENIED) {
        permissions.let { deniedPermissions.add(it[i]) }
      }
      return@foldIndexed acc && result == PackageManager.PERMISSION_GRANTED
    }

    return if (allPermissionGranted) {
      execute(action)
      true
    } else {
      onPermissionDenied(deniedPermissions)
      false
    }
  }

  private fun onPermissionDenied(deniedPermissions: List<String>) {
    result.error(PERMISSION_DENIED, PERMISSION_DENIED_MESSAGE, deniedPermissions)
  }

  fun setForegroundChannel(channel: MethodChannel) {
    foregroundChannel = channel
  }

  override fun onReceive(ctx: Context?, intent: Intent?) {
    if (intent != null) {
      when (intent.action) {
        Constants.ACTION_SMS_SENT -> foregroundChannel.invokeMethod(SMS_SENT, null)
        Constants.ACTION_SMS_DELIVERED -> {
          foregroundChannel.invokeMethod(SMS_DELIVERED, null)
          context.unregisterReceiver(this)
        }
      }
    }
  }

  class WorkManagerWorker(applicationContext: Context, private val workerParams: WorkerParameters,) : ListenableWorker(applicationContext, workerParams) {
    private val payload
      get() = workerParams.inputData.getString(PAYLOAD_KEY)

    private val dartTask
      get() = workerParams.inputData.getString(DART_TASK_KEY)!!

    private val randomThreadIdentifier = Random().nextInt()

    private var startTime: Long = 0

    private var completer: CallbackToFutureAdapter.Completer<Result>? = null

    private var resolvableFuture =
      CallbackToFutureAdapter.getFuture { completer ->
        this.completer = completer
        null
      }

    override fun startWork(): ListenableFuture<Result> {
      startTime = System.currentTimeMillis()
      IncomingSmsHandler.apply {
        if (!isIsolateRunning.get()) {
          initialize(applicationContext)
          val preferences =
            applicationContext.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
          val backgroundCallbackHandle =
            preferences.getLong(SHARED_PREFS_BACKGROUND_SETUP_HANDLE, 0)
          startBackgroundIsolate(applicationContext, backgroundCallbackHandle)
          completer?.set(Result.retry())
//          backgroundMessageQueue.add(sms)
        } else {
          executeWorkManagerCallbackInBackgroundIsolate(applicationContext, payload!!, dartTask, completer)
        }

      }


      return resolvableFuture
    }

    override fun getForegroundInfoAsync(): ListenableFuture<ForegroundInfo> {
      val imageId = applicationContext.resources.getIdentifier("ic_launcher", "mipmap", applicationContext.packageName)
      return CallbackToFutureAdapter.getFuture { completer ->
        val notification = NotificationCompat.Builder(applicationContext, "notifications_listener_channel")
          .setContentTitle("Network Task")
          .setContentText("Running in the foreground")
          .setSmallIcon(imageId)
          .setPriority(NotificationCompat.PRIORITY_MIN)
          .build()

        val foregroundInfo = ForegroundInfo(420, notification)
        completer.set(foregroundInfo)
      }
    }

  }
}
