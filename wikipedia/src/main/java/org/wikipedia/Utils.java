package org.wikipedia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.*;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;
import com.squareup.otto.Bus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mediawiki.api.json.ApiResult;
import org.wikipedia.bridge.CommunicationBridge;
import org.wikipedia.events.WikipediaZeroInterstitialEvent;
import org.wikipedia.events.WikipediaZeroStateChangeEvent;
import org.wikipedia.zero.WikipediaZeroTask;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Contains utility methods that Java doesn't have because we can't make code look too good, can we?
 */
public final class Utils {
    /**
     * Private constructor, so nobody can construct Utils.
     *
     * THEIR EVIL PLANS HAVE BEEN THWARTED!!!1
     */
    private Utils() { }

    /**
     * Compares two strings properly, even when one of them is null - without throwing up
     *
     * @param str1 The first string
     * @param str2 Guess?
     * @return true if they are both equal (even if both are null)
     */
    public static boolean compareStrings(String str1, String str2) {
        return (str1 == null ? str2 == null : str1.equals(str2));
    }

    /**
     * Creates an MD5 hash of the provided string & returns its base64 representation
     * @param s String to hash
     * @return Base64'd MD5 representation of the string passed in
     */
    public static String md5(final String s) {
        try {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest
                    .getInstance("MD5");
            digest.update(s.getBytes("utf-8"));
            byte[] messageDigest = digest.digest();

            return Base64.encodeToString(messageDigest, Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            // This will never happen, yes.
            throw new RuntimeException(e);
        } catch (UnsupportedEncodingException e) {
            // This will never happen, yes.
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the local file name for a remote image.
     *
     * Warning: Should be kept stable between releases.
     * @param url URL of the thumbnail image. Expects them to be not protocol relative & have an extension.
     * @return
     */
    public static String imageUrlToFileName(String url) {
        String[] protocolParts = url.split("://");
        return "saved-image-"
                + md5(protocolParts[protocolParts.length - 1]);
    }

    /**
     * Add some utility methods to a communuication bridge, that can be called synchronously from JS
     */
    public static void addUtilityMethodsToBridge(final Context context, final CommunicationBridge bridge) {
        bridge.addListener("imageUrlToFilePath", new CommunicationBridge.JSEventListener() {
            @Override
            public void onMessage(String messageType, JSONObject messagePayload) {
                String imageUrl = messagePayload.optString("imageUrl");
                JSONObject ret = new JSONObject();
                try {
                    File imageFile = new File(context.getFilesDir(), imageUrlToFileName(imageUrl));
                    ret.put("originalURL", imageUrl);
                    ret.put("newURL", imageFile.getAbsolutePath());
                    bridge.sendMessage("replaceImageSrc", ret);
                } catch (JSONException e) {
                    // stupid, stupid, stupid
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * Parses dates from the format MediaWiki uses.
     *
     * @param mwDate String representing Date returned from a MW API call
     * @return A {@link java.util.Date} object representing that particular date
     */
    public static Date parseMWDate(String mwDate) {
        SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"); // Assuming MW always gives me UTC
        try {
            return isoFormat.parse(mwDate);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Formats provided date relative to the current system time
     * @param date Date to format
     * @return String representing the relative time difference of the paramter from current time
     */
    public static String formatDateRelative(Date date) {
        return DateUtils.getRelativeTimeSpanString(date.getTime(), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, 0).toString();
    }

    /**
     * Ensures that the calling method is on the main thread.
     */
    public static void ensureMainThread() {
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException("Method must be called from the Main Thread");
        }
    }

    /**
     * Attempt to hide the Android Keyboard.
     *
     * FIXME: This should not need to exist.
     * I do not know why Android does not handle this automatically.
     *
     * @param activity The current activity
     */
    public static void hideSoftKeyboard(Activity activity) {
        InputMethodManager keyboard = (InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        // Not using getCurrentFocus as that sometimes is null, but the keyboard is still up.
        keyboard.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
    }

    public static void setupShowPasswordCheck(final CheckBox check, final EditText edit) {
        check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                // EditText loses the cursor position when you change the InputType
                int curPos = edit.getSelectionStart();
                if (isChecked) {
                    edit.setInputType(InputType.TYPE_CLASS_TEXT);
                } else {
                    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                }
                edit.setSelection(curPos);
            }
        });
    }

     /* Inspect an API response, and fire an event to update the UI for Wikipedia Zero On/Off.
     *
     * @param app The application object
     * @param result An API result to inspect for Wikipedia Zero headers
     */
    public static void processHeadersForZero(final WikipediaApp app, final ApiResult result) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Map<String, List<String>> headers = result.getHeaders();
                boolean responseZeroState = headers.containsKey("X-CS");
                if (responseZeroState) {
                    String xcs = headers.get("X-CS").get(0);
                    if (!xcs.equals(WikipediaApp.getXcs())) {
                        identifyZeroCarrier(app, xcs);
                    }
                } else if (WikipediaApp.getWikipediaZeroDisposition()) {
                    WikipediaApp.setXcs("");
                    WikipediaApp.setCarrierMessage("");
                    WikipediaApp.setWikipediaZeroDisposition(responseZeroState);
                    app.getBus().post(new WikipediaZeroStateChangeEvent());
                }
            }
        });
    }

    private static final int MESSAGE_ZERO = 1;

    public static void identifyZeroCarrier(final WikipediaApp app, final String xcs) {
        Handler wikipediaZeroHandler = new Handler(new Handler.Callback(){
            private WikipediaZeroTask curZeroTask;

            @Override
            public boolean handleMessage(Message msg) {
                WikipediaZeroTask zeroTask = new WikipediaZeroTask(app.getAPIForSite(app.getPrimarySite()), app) {
                    @Override
                    public void onFinish(String message) {
                        Log.d("Wikipedia", "Wikipedia Zero message: " + message);

                        if (message != null) {
                            WikipediaApp.setXcs(xcs);
                            WikipediaApp.setCarrierMessage(message);
                            WikipediaApp.setWikipediaZeroDisposition(true);
                            Bus bus = app.getBus();
                            bus.post(new WikipediaZeroStateChangeEvent());
                            curZeroTask = null;
                        }
                    }

                    @Override
                    public void onCatch(Throwable caught) {
                        // oh snap
                        Log.d("Wikipedia", "Wikipedia Zero Eligibility Check Exception Caught");
                        curZeroTask = null;
                    }
                };
                if (curZeroTask != null) {
                    // if this connection was hung, clean up a bit
                    curZeroTask.cancel();
                }
                curZeroTask = zeroTask;
                curZeroTask.execute();
                return true;
            }
        });

        wikipediaZeroHandler.removeMessages(MESSAGE_ZERO);
        Message zeroMessage = Message.obtain();
        zeroMessage.what = MESSAGE_ZERO;
        zeroMessage.obj = "zero_eligible_check";

        wikipediaZeroHandler.sendMessage(zeroMessage);
    }

    /**
     * Read the MCC-MNC (mobile operator code) if available and the cellular data connection is the active one.
     * http://lists.wikimedia.org/pipermail/wikimedia-l/2014-April/071131.html
     * @param ctx Application context.
     * @return The MCC-MNC, typically as ###-##, or null if unable to ascertain (e.g., no actively used cellular)
     */
    public static String getMccMnc(Context ctx) {
        String mccMnc = null;
        try {
            ConnectivityManager conn = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = conn.getActiveNetworkInfo();
            if (networkInfo != null && networkInfo.getState() == NetworkInfo.State.CONNECTED
                    && (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE || networkInfo.getType() == ConnectivityManager.TYPE_WIMAX))
            {
                TelephonyManager t = (TelephonyManager)ctx.getSystemService(WikipediaApp.TELEPHONY_SERVICE);
                if (t != null && t.getPhoneType() >= 0) {
                    mccMnc = t.getNetworkOperator();
                    if (mccMnc != null) {
                        mccMnc = mccMnc.substring(0,3) + "-" + mccMnc.substring(3);
                    }

                    // TelephonyManager documentation refers to MCC-MNC unreliability on CDMA,
                    // so we'll try to read the SIM and use the SIM MCC-MNC if there's a disagreement.
                    // There may be a counterargument to go the other way, although we'll go this route for now.
                    if (t.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                        String simMccMnc = t.getSimOperator();
                        if (simMccMnc != null) {
                            simMccMnc = simMccMnc.substring(0,3) + "-" + simMccMnc.substring(3);
                            if (!simMccMnc.equals(mccMnc)) {
                                mccMnc = simMccMnc;
                            }
                        }
                    }
                }
            }
            return mccMnc;
        } catch (Exception e) {
            // Because, despite best efforts, things can go wrong and we don't want to crash the app:
            return null;
        }
    }

    /**
     * Takes a language code (as returned by Android) and returns a wiki code, as used by wikipedia.
     *
     * @param langCode Language code (as returned by Android)
     * @return Wiki code, as used by wikipedia.
     */
    public static String langCodeToWikiLang(String langCode) {
        // Convert deprecated language codes to modern ones.
        // See https://developer.android.com/reference/java/util/Locale.html
        if (langCode.equals("iw")) {
            return "he"; // Hebrew
        } else if (langCode.equals("in")) {
            return "id"; // Indonesian
        } else if (langCode.equals("ji")) {
            return "yi"; // Yiddish
        }

        return langCode;
    }

    /**
     * List of wiki language codes for which the content is primarily RTL.
     *
     * Ensure that this is always sorted alphabetically.
     */
    private static final String[] RTL_LANGS = {
            "ar", "arc", "arz", "bcc", "bqi", "ckb", "dv", "fa", "glk", "ha", "he",
            "khw", "ks", "mzn", "pnb", "ps", "sd", "ug", "ur", "yi"
    };

    /**
     * Returns true if the given wiki language is to be displayed RTL.
     *
     * @param lang Wiki code for the language to check for directionality
     * @return true if it is RTL, false if LTR
     */
    public static boolean isLangRTL(String lang) {
        return Arrays.binarySearch(RTL_LANGS, lang, null) >= 0;
    }

    /**
     * Setup directionality for both UI and content elements in a webview.
     *
     * @param contentLang The Content language to use to set directionality. Wiki Language code.
     * @param uiLang The UI language to use to set directionality. Java language code.
     * @param bridge The CommunicationBridge to use to communicate with the WebView
     */
    public static void setupDirectionality(String contentLang, String uiLang, CommunicationBridge bridge) {
        JSONObject payload = new JSONObject();
        try {
            if (isLangRTL(contentLang)) {
                payload.put("contentDirection", "rtl");
            } else {
                payload.put("contentDirection", "ltr");
            }
            if (isLangRTL(langCodeToWikiLang(uiLang))) {
                payload.put("uiDirection", "rtl");
            } else {
                payload.put("uiDirection", "ltr");
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        bridge.sendMessage("setDirectionality", payload);
    }

    /**
     * Sets text direction (RTL / LTR) for given view based on given lang.
     *
     * Doesn't do anything on pre Android 4.2, since their RTL support is terrible.
     *
     * @param view View to set direction of
     * @param lang Wiki code for the language based on which to set direction
     */
    public static void setTextDirection(View view, String lang) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.setTextDirection(Utils.isLangRTL(lang) ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        }
    }

    /**
     * Returns db name for given site
     *
     * WARNING: HARDCODED TO WORK FOR WIKIPEDIA ONLY
     *
     * @param site Site object to get dbname for
     * @return dbname for given site object
     */
    public static String getDBNameForSite(Site site) {
        return site.getLanguage() + "wiki";
    }

    public static void handleExternalLink(final Context context, final Uri uri) {
        if (WikipediaApp.isWikipediaZeroDevmodeOn() && WikipediaApp.getWikipediaZeroDisposition()) {
            SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            if (sharedPref.getBoolean(WikipediaApp.PREFERENCE_ZERO_INTERSTITIAL, true)) {
                WikipediaApp.getInstance().getBus().post(new WikipediaZeroInterstitialEvent(uri));
            } else {
                Utils.visitInExternalBrowser(context, uri);
            }
        } else {
            Utils.visitInExternalBrowser(context, uri);
        }
    }

    /**
     * Open the specified URI in an external browser (even if our app's intent filter
     * matches the given URI)
     *
     * @param context Context of the calling app
     * @param uri URI to open in an external browser
     */
    public static void visitInExternalBrowser(final Context context, Uri uri) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(uri);
        List<ResolveInfo> resInfo = context.getPackageManager().queryIntentActivities(intent, 0);
        if (!resInfo.isEmpty()) {
            List<Intent> browserIntents = new ArrayList<Intent>();
            for (ResolveInfo resolveInfo : resInfo) {
                String packageName = resolveInfo.activityInfo.packageName;
                // remove our app from the selection!
                if (packageName.equals(context.getPackageName())) {
                    continue;
                }
                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                newIntent.setData(uri);
                newIntent.setPackage(packageName);
                browserIntents.add(newIntent);
            }
            if (browserIntents.size() > 0) {
                // initialize the chooser intent with one of the browserIntents, and remove that
                // intent from the list, since the chooser already has it, and we don't need to
                // add it again in putExtra. (initialize with the last item in the list, to preserve order)
                Intent chooserIntent = Intent.createChooser(browserIntents.remove(browserIntents.size() - 1), null);
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, browserIntents.toArray(new Parcelable[]{}));
                context.startActivity(chooserIntent);
                return;
            }
        }
        // This means that there was no way to handle this link.
        // We will just show a toast now. FIXME: Make this more visible?
        Toast.makeText(context, R.string.error_can_not_process_link, Toast.LENGTH_LONG).show();
    }

    /**
     * Utility method to copy a stream into another stream.
     *
     * Uses a 16KB buffer.
     *
     * @param in Stream to copy from.
     * @param out Stream to copy to.
     * @throws IOException
     */
    public static void copyStreams(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024]; // 16kb buffer
        int len;
        while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
    }

    /**
     * Format for formatting/parsing dates to/from the ISO 8601 standard
     */
    private static final String ISO8601_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    /**
     * Parse a date formatted in ISO8601 format.
     *
     * @param dateString Date String to parse
     * @return Parsed Date object.
     * @throws ParseException
     */
    public static Date parseISO8601(String dateString) throws ParseException {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat(ISO8601_FORMAT_STRING);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        date.setTime(sdf.parse(dateString).getTime());
        return date;
    }

    /**
     * Format a date to an ISO8601 formatted string.
     *
     * @param date Date to format.
     * @return The given date formatted in ISO8601 format.
     */
    public static String formatISO8601(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat(ISO8601_FORMAT_STRING);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        return sdf.format(date);
    }

    /**
     * Convert a JSONArray object to a String Array.
     *
     * @param array A JSONArray containing only Strings
     * @return A String[] with all the items in the JSONArray
     */
    public static String[] JSONArrayToStringArray(JSONArray array) {
        if (array == null) {
            return null;
        }
        String[] stringArray = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            stringArray[i] = array.optString(i);
        }
        return stringArray;
    }

    /**
     * Detects whether the current device has a low-resolution screen
     * (defined using a hard-coded threshold for low-resolution)
     *
     * @param ctx Application context for detecting screen size.
     * @return True if this is a low-resolution device; false otherwise.
     */
    public static boolean isLowResolutionDevice(Context ctx) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        return (display.getHeight() <= 480);
    }
}
