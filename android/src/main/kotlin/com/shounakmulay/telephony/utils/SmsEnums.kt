package com.shounakmulay.telephony.utils

import android.net.Uri
import android.provider.Telephony

enum class SmsAction(private val methodName: String) {
  GET_INBOX("getAllInboxSms"),
  GET_SENT("getAllSentSms"),
  GET_DRAFT("getAllDraftSms"),
  GET_CONVERSATIONS("getAllConversations"),
  SEND_SMS("sendSms"),
  SEND_MULTIPART_SMS("sendMultipartSms"),
  SEND_SMS_INTENT("sendSmsIntent"),
  START_BACKGROUND_SERVICE("startBackgroundService"),
  DISABLE_BACKGROUND_SERVICE("disableBackgroundService"),
  BACKGROUND_SERVICE_INITIALIZED("backgroundServiceInitialized"),
  IS_SMS_CAPABLE("isSmsCapable"),
  GET_CELLULAR_DATA_STATE("getCellularDataState"),
  GET_CALL_STATE("getCallState"),
  GET_DATA_ACTIVITY("getDataActivity"),
  GET_NETWORK_OPERATOR("getNetworkOperator"),
  GET_NETWORK_OPERATOR_NAME("getNetworkOperatorName"),
  GET_DATA_NETWORK_TYPE("getDataNetworkType"),
  GET_PHONE_TYPE("getPhoneType"),
  GET_SIM_OPERATOR("getSimOperator"),
  GET_SIM_OPERATOR_NAME("getSimOperatorName"),
  GET_SIM_STATE("getSimState"),
  GET_SERVICE_STATE("getServiceState"),
  GET_SIGNAL_STRENGTH("getSignalStrength"),
  IS_NETWORK_ROAMING("isNetworkRoaming"),
  REQUEST_SMS_PERMISSIONS("requestSmsPermissions"),
  REQUEST_PHONE_PERMISSIONS("requestPhonePermissions"),
  REQUEST_PHONE_AND_SMS_PERMISSIONS("requestPhoneAndSmsPermissions"),
  OPEN_DIALER("openDialer"),
  DIAL_PHONE_NUMBER("dialPhoneNumber"),
  SET_AS_DEFAULT_SMS_APP("setAsDefaultSmsApp"),
  GET_DEFAULT_SMS_APP("getDefaultSmsApp"),
  SET_NOTIFICATION_PERMISSION("setNotificationPermission"),
  GET_NOTIFICATION_PERMISSION("getNotificationPermission"),
  RUN_WORKMANAGER_TASK("runWorkManagerTask"),
  CANCEL_WORKMANAGER_TASKS("cancelWorkManagerTasks"),
  NO_SUCH_METHOD("noSuchMethod");

  companion object {
    fun fromMethod(method: String): SmsAction {
      for (action in values()) {
        if (action.methodName == method) {
          return action
        }
      }
      return NO_SUCH_METHOD
    }
  }

  fun toActionType(): ActionType {
    return when (this) {
      GET_INBOX,
      GET_SENT,
      GET_DRAFT,
      GET_CONVERSATIONS -> ActionType.GET_SMS
      SEND_SMS,
      SEND_MULTIPART_SMS,
      SEND_SMS_INTENT,
      NO_SUCH_METHOD -> ActionType.SEND_SMS
      RUN_WORKMANAGER_TASK,
      START_BACKGROUND_SERVICE,
      DISABLE_BACKGROUND_SERVICE,
      BACKGROUND_SERVICE_INITIALIZED -> ActionType.BACKGROUND
      IS_SMS_CAPABLE,
      GET_CELLULAR_DATA_STATE,
      GET_CALL_STATE,
      GET_DATA_ACTIVITY,
      GET_NETWORK_OPERATOR,
      GET_NETWORK_OPERATOR_NAME,
      GET_DATA_NETWORK_TYPE,
      GET_PHONE_TYPE,
      GET_SIM_OPERATOR,
      GET_SIM_OPERATOR_NAME,
      GET_SIM_STATE,
      GET_SERVICE_STATE,
      GET_SIGNAL_STRENGTH,
      GET_DEFAULT_SMS_APP,
      IS_NETWORK_ROAMING -> ActionType.GET
      REQUEST_SMS_PERMISSIONS,
      REQUEST_PHONE_PERMISSIONS,
      REQUEST_PHONE_AND_SMS_PERMISSIONS -> ActionType.PERMISSION
      OPEN_DIALER,
      DIAL_PHONE_NUMBER -> ActionType.CALL
      CANCEL_WORKMANAGER_TASKS -> ActionType.CANCEL_WORKMANAGER_TASKS
      GET_NOTIFICATION_PERMISSION -> ActionType.NOTIFICATION_PERMISSION
      SET_NOTIFICATION_PERMISSION -> ActionType.OPEN_SETTINGS
      SET_AS_DEFAULT_SMS_APP -> ActionType.CHANGE_DEFAULT
    }
  }
}

enum class ActionType {
  GET_SMS, SEND_SMS, BACKGROUND, GET, PERMISSION, CALL, CHANGE_DEFAULT, OPEN_SETTINGS, NOTIFICATION_PERMISSION, CANCEL_WORKMANAGER_TASKS
}

enum class ContentUri(val uri: Uri) {
  INBOX(Telephony.Sms.Inbox.CONTENT_URI),
  SENT(Telephony.Sms.Sent.CONTENT_URI),
  DRAFT(Telephony.Sms.Draft.CONTENT_URI),
  CONVERSATIONS(Telephony.Sms.Conversations.CONTENT_URI);
}