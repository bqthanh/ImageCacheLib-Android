package vn.hbs.debug;

import android.util.Log;

/**
 * Created by thanhbui on 2017/03/13.
 */

public class DebugLog {

    public static void i(String tag, String msg) {
        Log.i(safeToString(tag), safeToString(msg));
    }

    public static void e(String tag, String msg) {
        Log.e(safeToString(tag), safeToString(msg));
    }

    private static String safeToString(Object o) {
        if (o == null) {
            return "";
        } else {
            return o.toString();
        }
    }
}