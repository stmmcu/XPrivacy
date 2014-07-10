package biz.bokhorst.xprivacy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.nfc.NfcAdapter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Message;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.telephony.TelephonyManager;
import android.util.Log;

@SuppressLint("InlinedApi")
public class XActivityThread extends XHook {
	private Methods mMethod;
	private static Map<String, String> mapActionRestriction = new HashMap<String, String>();
	private static final String GMS_LOCATION_CHANGED = "com.google.android.location.LOCATION";

	static {
		// Intent receive: calling
		mapActionRestriction.put(Intent.ACTION_NEW_OUTGOING_CALL, PrivacyManager.cCalling);
		mapActionRestriction.put(TelephonyManager.ACTION_PHONE_STATE_CHANGED, PrivacyManager.cPhone);
		mapActionRestriction.put(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, PrivacyManager.cCalling);

		// Intent receive: C2DM
		mapActionRestriction.put("com.google.android.c2dm.intent.REGISTRATION", PrivacyManager.cNotifications);
		mapActionRestriction.put("com.google.android.c2dm.intent.RECEIVE", PrivacyManager.cNotifications);

		// Intent receive: NFC
		mapActionRestriction.put(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED, PrivacyManager.cNfc);
		mapActionRestriction.put(NfcAdapter.ACTION_NDEF_DISCOVERED, PrivacyManager.cNfc);
		mapActionRestriction.put(NfcAdapter.ACTION_TAG_DISCOVERED, PrivacyManager.cNfc);
		mapActionRestriction.put(NfcAdapter.ACTION_TECH_DISCOVERED, PrivacyManager.cNfc);

		// Intent receive: SMS
		mapActionRestriction.put(Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION, PrivacyManager.cMessages);
		mapActionRestriction.put(Telephony.Sms.Intents.SMS_RECEIVED_ACTION, PrivacyManager.cMessages);
		mapActionRestriction.put(Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION, PrivacyManager.cMessages);

		// Intent receive: notifications
		mapActionRestriction.put(NotificationListenerService.SERVICE_INTERFACE, PrivacyManager.cNotifications);

		// Intent receive: package changes
		mapActionRestriction.put(Intent.ACTION_PACKAGE_ADDED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_REPLACED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_RESTARTED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_REMOVED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_CHANGED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_DATA_CLEARED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_FIRST_LAUNCH, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_FULLY_REMOVED, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE, PrivacyManager.cSystem);
		mapActionRestriction.put(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE, PrivacyManager.cSystem);
	}

	private XActivityThread(Methods method, int sdk) {
		super(null, method.name(), null, sdk);
		mMethod = method;
	}

	public String getClassName() {
		return (mMethod == Methods.handleReceiver ? "android.app.ActivityThread" : "android.os.MessageQueue");
	}

	@Override
	public boolean isVisible() {
		return false;
	}

	private enum Methods {
		next, handleReceiver
	};

	// @formatter:off

	// private void handleReceiver(ReceiverData data)
	// frameworks/base/core/java/android/app/ActivityThread.java

	// final Message next()
	// frameworks/base/core/java/android/android/os/MessageQueue.java

	// @formatter:on

	public static List<XHook> getInstances() {
		List<XHook> listHook = new ArrayList<XHook>();

		listHook.add(new XActivityThread(Methods.next, 1));
		listHook.add(new XActivityThread(Methods.handleReceiver, 1));

		return listHook;
	}

	@Override
	protected void before(XParam param) throws Throwable {
		if (mMethod == Methods.next) {
			// Do nothing

		} else if (mMethod == Methods.handleReceiver) {
			if (param.args.length > 0 && param.args[0] != null) {
				Field fieldIntent = param.args[0].getClass().getDeclaredField("intent");
				fieldIntent.setAccessible(true);
				Intent intent = (Intent) fieldIntent.get(param.args[0]);
				if (intent != null) {
					if (checkIntentAction(Binder.getCallingUid(), intent)) {
						finish(param);
						param.setResult(null);
					}
				}
			}

		} else
			Util.log(this, Log.WARN, "Unknown method=" + param.method.getName());
	}

	@Override
	protected void after(XParam param) throws Throwable {
		if (mMethod == Methods.next) {
			Message msg = (Message) param.getResult();
			if (msg != null) {
				if (msg.obj instanceof Intent) {
					Intent intent = (Intent) msg.obj;
					if (intent != null)
						checkIntentExtras(intent);
				}
			}

		} else if (mMethod == Methods.handleReceiver) {
			// Do nothing

		} else
			Util.log(this, Log.WARN, "Unknown method=" + param.method.getName());
	}

	private boolean checkIntentAction(int uid, Intent intent) throws Throwable {
		String action = intent.getAction();
		if (mapActionRestriction.containsKey(action)) {
			// Get restriction category
			String restrictionName = mapActionRestriction.get(action);

			if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
				// Outgoing call
				Bundle bundle = intent.getExtras();
				if (bundle != null) {
					String phoneNumber = bundle.getString(Intent.EXTRA_PHONE_NUMBER);
					if (phoneNumber != null)
						if (isRestrictedExtra(uid, restrictionName, action, phoneNumber))
							intent.putExtra(Intent.EXTRA_PHONE_NUMBER,
									(String) PrivacyManager.getDefacedProp(Binder.getCallingUid(), "PhoneNumber"));
				}

			} else if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
				// Incoming call
				Bundle bundle = intent.getExtras();
				if (bundle != null) {
					String phoneNumber = bundle.getString(TelephonyManager.EXTRA_INCOMING_NUMBER);
					if (phoneNumber != null) {
						if (isRestrictedExtra(uid, restrictionName, action, phoneNumber))
							intent.putExtra(TelephonyManager.EXTRA_INCOMING_NUMBER,
									(String) PrivacyManager.getDefacedProp(Binder.getCallingUid(), "PhoneNumber"));
					}
				}

			} else if (PrivacyManager.cSystem.equals(restrictionName)) {
				// Package event
				if (isRestrictedExtra(uid, restrictionName, action, intent.getDataString())) {
					String[] packageNames;
					if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
							|| action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE))
						packageNames = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
					else
						packageNames = new String[] { intent.getData().getSchemeSpecificPart() };
					for (String packageName : packageNames)
						if (!XPackageManager.isPackageAllowed(packageName))
							return true;
				}

			} else if (isRestrictedExtra(uid, restrictionName, action, intent.getDataString()))
				return true;

		} else
			checkIntentExtras(intent);

		return false;
	}

	private void checkIntentExtras(Intent intent) throws Throwable {
		if (intent.hasExtra(LocationManager.KEY_LOCATION_CHANGED)) {
			int uid = Binder.getCallingUid();
			Location location = (Location) intent.getExtras().get(LocationManager.KEY_LOCATION_CHANGED);
			Location fakeLocation = PrivacyManager.getDefacedLocation(uid, location);
			if (getRestricted(uid, PrivacyManager.cLocation, "requestLocationUpdates"))
				intent.putExtra(LocationManager.KEY_LOCATION_CHANGED, fakeLocation);
		}

		else if (intent.hasExtra(GMS_LOCATION_CHANGED)) {
			int uid = Binder.getCallingUid();
			Location location = (Location) intent.getExtras().get(GMS_LOCATION_CHANGED);
			Location fakeLocation = PrivacyManager.getDefacedLocation(uid, location);
			if (getRestricted(uid, PrivacyManager.cLocation, "GMS.requestLocationUpdates"))
				intent.putExtra(GMS_LOCATION_CHANGED, fakeLocation);
		}
	}

	private void finish(XParam param) {
		// unscheduleGcIdler
		if (param.thisObject != null)
			try {
				Method unschedule = param.thisObject.getClass().getDeclaredMethod("unscheduleGcIdler");
				unschedule.setAccessible(true);
				unschedule.invoke(param.thisObject);
			} catch (Throwable ex) {
				Util.bug(this, ex);
			}

		// data.finish
		if (param.args[0] instanceof BroadcastReceiver.PendingResult)
			try {
				BroadcastReceiver.PendingResult pr = (BroadcastReceiver.PendingResult) param.args[0];
				pr.finish();
			} catch (IllegalStateException ignored) {
				// No receivers for action ...
			} catch (Throwable ex) {
				Util.bug(this, ex);
			}
	}
}
