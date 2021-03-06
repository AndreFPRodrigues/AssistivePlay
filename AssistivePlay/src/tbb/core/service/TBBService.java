package tbb.core.service;

import blackbox.external.logger.DataWriter;
import tbb.core.CoreController;
import tbb.core.ioManager.Monitor;
import tbb.touch.TPRNexusS;
import tbb.touch.TPRTab2;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;

import android.content.BroadcastReceiver;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * TinyBlackBoxService
 * 
 * This service launches all other modules of TBB. Also, it forwards all
 * accessibility events triggered by the Android
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class TBBService extends AccessibilityService {

	// logging tag
	public static final String TAG = "TinyBlackBox";
	private final String SUBTAG = "TBBService: ";

	// service's preferences
	private static SharedPreferences mSharedPref;
	private PreferenceChangeListener mSharedPrefsListener = null; // preferences
																	// listener
	public final static String PREF_TOUCH_RECOGNIZER = "BB.PREFERENCE.TOUCH_RECOGNIZER";
	public final static String PREF_LOGGING = "BB.PREFERENCE.LOGGING";
	public final static String PREF_WIDTH = "BB.PREFERENCE.WIDTH";
	public final static String PREF_HEIGHT = "BB.PREFERENCE.HEIGHT";
	public final static String PREF_TOUCH_DEVICE_INDEX = "BB.PREFERENCE.TOUCH_DEVICE_INDEX";

	// service broadcast actions
	public final static String ACTION_SCREEN_ON = "BB.ACTION.SCREEN_ON";
	public final static String ACTION_SCREEN_OFF = "BB.ACTION.SCREEN_OFF";
	public final static String ACTION_POWER_CONNECTED = "android.intent.action.ACTION_POWER_CONNECTED";
	public final static String ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED";

	// TODO description and remove dependency
	private Monitor mMonitor;

	// Screen on/off receiver
	private BroadcastReceiver mScreenReceiver = null;

	// Battery status receiver (detect charing)
	// private BroadcastReceiver mBatteryReceiver = null;

	// TODO why do we have this variable?
	// notification flags
	private boolean mNoteCheck = false;

	// flag to register keystrokes
	// TODO why is this here?
	private static boolean mLogAtTouch = true;

	// TBB main storage folder. This should be used throughout the service
	public static final String STORAGE_FOLDER = Environment
			.getExternalStorageDirectory().toString() + "/TBB";
	public static final String ERROR_FILE = "err_log.txt";

	public static boolean isRunning = false;

	public static Context context;

	/**
	 * Method called when the service is connected. It initializes the touch
	 * Monitor and CoreController.
	 */
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	@Override
	public void onServiceConnected() {
		try {
			context = this;

			Log.d(TAG, SUBTAG + "onServiceConnected() - connected");

			AccessibilityServiceInfo aci = new AccessibilityServiceInfo();
			aci.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
					| AccessibilityEvent.TYPE_VIEW_SCROLLED
					| AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
					| AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
					| AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
					| AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED;
			aci.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
			aci.flags = aci.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
					| aci.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
					| aci.FLAG_REQUEST_FILTER_KEY_EVENTS
					| aci.FLAG_REPORT_VIEW_IDS;
			setServiceInfo(aci);

			// get shared preferences
			mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
			mSharedPrefsListener = new PreferenceChangeListener();
			mSharedPref
					.registerOnSharedPreferenceChangeListener(mSharedPrefsListener);

			// listen for screen on/off actions
			IntentFilter listenerFilter = new IntentFilter();
			listenerFilter.addAction(Intent.ACTION_SCREEN_ON);
			listenerFilter.addAction(Intent.ACTION_SCREEN_OFF);

			registerReceiver(mScreenReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					// if preferences is set to logging then forward screen
					// on/off events
					if (mSharedPref.getBoolean(PREF_LOGGING, true)) {

						Intent toBroadcastIntent = new Intent();
						if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
							toBroadcastIntent.setAction(ACTION_SCREEN_OFF);
							sendBroadcast(toBroadcastIntent);

						} else if (intent.getAction().equals(
								Intent.ACTION_SCREEN_ON)) {
							toBroadcastIntent.setAction(ACTION_SCREEN_ON);
							sendBroadcast(toBroadcastIntent);

						}
					}
				}
			}, listenerFilter);

			// initializes all modules of TBB service
			initializeModules();

		} catch (Exception e) {
			Toast.makeText(getApplicationContext(), "TBB Exception",
					Toast.LENGTH_LONG).show();
			writeToErrorLog(e);
		}
	}

	/**
	 * onInterrupt is called when TBB is interrupted. It stops all modules of
	 * TBB.
	 */
	@Override
	public void onInterrupt() {

		/*
		 * try { stopService(); } catch(Exception e){
		 * Toast.makeText(getApplicationContext(), "TBB Exception",
		 * Toast.LENGTH_LONG).show();
		 * writeToErrorLog(Log.getStackTraceString(e)); }
		 */
	}

	/**
	 * onDestroy is called when TBB is destroyed. It stops all modules of TBB.
	 */
	@Override
	public void onDestroy() {
		try {

			stopService();
		} catch (Exception e) {
			Toast.makeText(getApplicationContext(), "TBB Exception",
					Toast.LENGTH_LONG).show();
			writeToErrorLog(e);
		}
	}

	/*
	 * Stops CoreController, which stops all TBB modules, and stops listening
	 * for screen events.
	 */
	private void stopService() {
		unregisterReceiver(mScreenReceiver);
		CoreController.sharedInstance().stopService();
		Log.v("IMPORTANT", "Monitor has been stopped");
		mMonitor.stop();
		this.stopSelf();
	}

	public void stopRequest() {
		CoreController.sharedInstance().stopService();
		Log.v("IMPORTANT", "Monitor has been stopped");
		mMonitor.stop();
		this.stopSelf();
	}

	/**
	 * Initializes CoreController and touch Monitor. It checks whether the touch
	 * driver is configured. If not, the user is prompted to the settings
	 * activity.
	 */
	private void initializeModules() {
		// starts touch recogniser
		// if it's unable to initialize then stops initialization process
		// TODO hardcoded values for type of device
		// TODO make a list of supported devices and match with
		// android.os.Build.Model ...
		if (!configureTouchRecogniser())
			return;

		// initialise monitor
		// TODO remove dependency of Monitor by initializing it in
		// CoreController
		mMonitor = new Monitor(-1);

		// initialise coreController
		CoreController.sharedInstance().initialize(mMonitor, this);

	}

	/**
	 * Checks whether the touch driver is configured. If not, the user is
	 * prompted to the settings activity.
	 * 
	 * @return true if touch driver is configured, false otherwise
	 */
	private boolean configureTouchRecogniser() {
		String tpr = mSharedPref.getString(PREF_TOUCH_RECOGNIZER, "null");
		if (tpr.equalsIgnoreCase("nexusS")) {
			Log.v(TAG, SUBTAG + "nexus");
			CoreController.sharedInstance().registerActivateTouch(
					new TPRNexusS());
		} else if (tpr.equalsIgnoreCase("tab2")) {
			Log.v(TAG, SUBTAG + "tab");
			CoreController.sharedInstance()
					.registerActivateTouch(new TPRTab2());
		} else {
			Log.v(TAG, SUBTAG + "null");
			if (tpr.equals("null")) {
				Toast.makeText(this,
						"Go to settings and select touch recogniser module",
						Toast.LENGTH_LONG).show();
				Intent i = new Intent(getBaseContext(),
						TBBPreferencesActivity.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				getApplication().startActivity(i);
				return false;
			}
		}
		return true;
	}

	/**
	 * Triggers whenever an event happens (changeWindow, focus, slide). Updates
	 * the current top parent of the screen contents. All accessibility events
	 * are forward to CoreController and touch Monitor
	 * 
	 * @param event
	 *            Accessibility event associated with a screen update
	 */
	@Override
	public void onAccessibilityEvent(AccessibilityEvent event) {
		try {
			// forwards all accessibility events
			CoreController.sharedInstance().updateAccessibilityEventReceivers(
					event);

			int eventType = event.getEventType();
			// if notification, then update all receivers
			// TODO why is this being done here? shouldn't this be done inside
			// the core controller
			// TODO ins't this an accessibility event type, just any other?
			// since it already receives the event
			if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
					&& mNoteCheck) {
				CoreController.sharedInstance().updateNotificationReceivers(
						String.valueOf(event.getText()));
				return;
			}

			// register keystrokes
			// TODO hardcorded "TEXT"
			if (AccessibilityEvent.eventTypeToString(eventType)
					.contains("TEXT")) {
				if (mLogAtTouch && !event.isPassword()) {
					// TODO ??
					if (event.getRemovedCount() > event.getAddedCount())
						mMonitor.registerKeystroke("<<" + ","
								+ event.getEventTime());
					else {

						if (event.getRemovedCount() != event.getAddedCount()) {
							// When the before text is a space it needs this
							// to
							// properly
							// detect the backspace
							// Bug a string a char follow by a space "t "
							// when
							// using
							// backspace it detects "t" instead of backspace
							if ((event.getText().size() - 2) == event
									.getBeforeText().length()
									|| (event.getAddedCount() - event
											.getRemovedCount()) > 1)
								mMonitor.registerKeystroke("<<" + ","
										+ event.getEventTime());
							else {
								String keypressed = event.getText().toString();
								keypressed = ""
										+ keypressed
												.charAt(keypressed.length() - 2);
								/*
								 * if (keypressed.equals(" ")) keypressed = " ";
								 * else keypressed = "x";
								 */
								mMonitor.registerKeystroke(keypressed + ","
										+ event.getEventTime());
							}
						}

					}
				}

			}
		} catch (Exception e) {
			Toast.makeText(getApplicationContext(), "TBB Exception",
					Toast.LENGTH_LONG).show();
			writeToErrorLog(e);
		}
	}

	/**
	 * Forces the system to go to home screen.
	 * 
	 * @return Whether the action was successfully performed.
	 */
	public boolean home() {
		return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);

	}

	/**
	 * Forces the system to go back.
	 * 
	 * @return Whether the action was successfully performed.
	 */
	public boolean back() {
		return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
	}

	/**
	 * Stores screen size.
	 * 
	 * @param width
	 *            Screen's width (in pixels)
	 * @param height
	 *            Screen's height (in pixels)
	 */
	public void storeScreenSize(int width, int height) {
		mSharedPref.edit().putInt(PREF_WIDTH, width).commit();
		mSharedPref.edit().putInt(PREF_HEIGHT, height).commit();
	}

	/**
	 * Retrieve screen size in pixels.
	 * 
	 * @return an array with width on position 0 and height on position 1
	 */
	public int[] getScreenSize() {
		int[] ret = new int[2];
		ret[0] = mSharedPref.getInt(PREF_WIDTH, 460);
		ret[1] = mSharedPref.getInt(PREF_HEIGHT, 800);
		return ret;
	}

	/**
	 * Stores the touch device index to the shared preferences
	 * 
	 * @param index
	 */
	public void storeTouchIndex(int index) {
		mSharedPref.edit().putInt(PREF_TOUCH_DEVICE_INDEX, index).commit();
	}

	/**
	 * Writes message to error log file
	 */
	public static void writeToErrorLog(String message) {
		ArrayList<String> data = new ArrayList<String>();
		data.add(message);
		DataWriter dw = new DataWriter(data, STORAGE_FOLDER, STORAGE_FOLDER
				+ "/" + ERROR_FILE, true, true);
	}

	/**
	 * Handle preference changes from Settings Activity.
	 */
	private class PreferenceChangeListener implements
			SharedPreferences.OnSharedPreferenceChangeListener {

		@Override
		public void onSharedPreferenceChanged(
				SharedPreferences sharedPreferences, String key) {
			try {
				if (key.equalsIgnoreCase(PREF_TOUCH_RECOGNIZER)) {
					String value = sharedPreferences.getString(
							PREF_TOUCH_RECOGNIZER, "null");

					// TODO check value, if null then stop services
					if (value.equalsIgnoreCase("null"))
						CoreController.sharedInstance().stopService();
					else
						initializeModules();
				}
			} catch (Exception e) {
				Toast.makeText(getApplicationContext(), "TBB Exception",
						Toast.LENGTH_LONG).show();
				writeToErrorLog(e);
			}
		}

	}

	public static void writeToErrorLog(Exception e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		writeToErrorLog(System.currentTimeMillis() + " \n" + sw.toString());
	}

	public static Display getDisplay() {
		Display display = ((WindowManager) context
				.getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

		return display;
	}
}
