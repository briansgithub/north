package com.north.compass;

import android.content.Context;
import android.content.SharedPreferences;

final class WidgetStore {
    static final String MAGNETIC = "magnetic";
    static final String TRUE = "true";
    static final String GRID = "grid";

    private final SharedPreferences prefs;

    WidgetStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("north_compass", Context.MODE_PRIVATE);
    }

    void save(int widgetId, String mode) {
        prefs.edit().putString(modeKey(widgetId), mode).apply();
    }

    String mode(int widgetId) {
        return prefs.getString(modeKey(widgetId), MAGNETIC);
    }

    void delete(int widgetId) {
        prefs.edit().remove(modeKey(widgetId)).apply();
    }

    void savePreview(String mode) {
        prefs.edit().putString("preview_mode", mode).apply();
    }

    String previewMode() {
        return prefs.getString("preview_mode", MAGNETIC);
    }

    static boolean needsDeclination(String mode) {
        return TRUE.equals(mode) || GRID.equals(mode);
    }

    private static String modeKey(int widgetId) {
        return "mode_" + widgetId;
    }
}
