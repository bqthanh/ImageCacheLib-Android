package vn.hbs.lib.debug;

import android.util.Log;

import vn.hbs.BuildConfig;

/**
 * Created by thanhbui on 2017/03/13.
 */

public class DebugLog {

    public static void i(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.i(safeToString(tag), safeToString(msg));
        }
    }

    public static void d(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.d(safeToString(tag), safeToString(msg));
        }
    }

    public static void e(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            Log.e(safeToString(tag), safeToString(msg));
        }
    }

    private static String safeToString(Object o) {
        if (o == null) {
            return "";
        } else {
            return o.toString();
        }
    }
}