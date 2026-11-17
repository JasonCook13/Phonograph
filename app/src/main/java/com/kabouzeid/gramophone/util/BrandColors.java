package com.kabouzeid.gramophone.util;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import com.kabouzeid.appthemehelper.ThemeStore;

/** The app icon's palette: burgundy, gold and cream. */
public final class BrandColors {
    @ColorInt public static final int BURGUNDY = 0xFF7A1E33;
    @ColorInt public static final int BURGUNDY_DARK = 0xFF5C1626;
    @ColorInt public static final int GOLD = 0xFFD6AC52;
    /** Darker gold that stays readable as text on light backgrounds. */
    @ColorInt public static final int GOLD_DEEP = 0xFF8F6A1F;
    @ColorInt public static final int CREAM = 0xFFF2ECE0;

    private BrandColors() {
    }

    /** Gold for text and buttons: bright on dark themes, deep on light ones. */
    @ColorInt
    public static int goldText(@NonNull Context context) {
        android.content.res.TypedArray a = context.getTheme().obtainStyledAttributes(new int[]{android.R.attr.colorBackground});
        int bg = a.getColor(0, Color.WHITE);
        a.recycle();
        return isLight(bg) ? GOLD_DEEP : GOLD;
    }

    private static boolean isLight(@ColorInt int c) {
        double l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255;
        return l > 0.5;
    }

    /** Applies burgundy and gold once (theme version 2), replacing the old indigo and pink defaults. */
    public static void applyDefaultTheme(@NonNull Context context) {
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean("brand_cream_default_v1", false)) {
            // Cream (the light theme) as the default look, once; can be changed in Settings.
            PreferenceUtil.getInstance(context).setGeneralTheme("light");
            prefs.edit().putBoolean("brand_cream_default_v1", true).apply();
        }
        if (!ThemeStore.isConfigured(context, 2)) {
            ThemeStore.editTheme(context)
                    .primaryColor(BURGUNDY)
                    .accentColor(GOLD)
                    .commit();
        }
    }
}
