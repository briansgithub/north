package com.north.compass;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.util.SparseArray;
import android.widget.RemoteViews;

final class CompassWidgets {
    private static final SparseArray<Float> LAST_ROTATION = new SparseArray<>();
    private static final SparseArray<Float> LAST_ALPHA = new SparseArray<>();
    private static final SparseArray<Long> LAST_PUSH = new SparseArray<>();

    private CompassWidgets() {}

    static void render(Context context, boolean full) {
        synchronized (CompassWidgets.class) {
            renderLocked(context, full);
        }
    }

    static void forget(int widgetId) {
        synchronized (CompassWidgets.class) {
            LAST_ROTATION.delete(widgetId);
            LAST_ALPHA.delete(widgetId);
            LAST_PUSH.delete(widgetId);
        }
    }

    private static void renderLocked(Context context, boolean full) {
        Declination.ensureLoaded(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CompassWidgetProvider.class));
        WidgetStore store = new WidgetStore(context);
        Location here = CompassService.location();
        boolean headingOk = CompassService.hasHeading();
        float heading = CompassService.heading();
        float uptown = Float.NaN;
        if (here != null) {
            uptown = Avenues.uptownTrue(here.getLatitude(), here.getLongitude());
        }
        for (int id : ids) {
            String mode = store.mode(id);
            float rotation = 0f;
            float alpha = 0.35f;
            float declination = Declination.east();
            if (headingOk && (!WidgetStore.needsDeclination(mode) || !Float.isNaN(declination))) {
                rotation = Heading.arrowRotation(heading, mode, declination, uptown);
                alpha = 1f;
            }
            if (!full && !shouldPush(id, rotation, alpha)) {
                continue;
            }
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_compass);
            views.setFloat(R.id.arrow, "setRotation", rotation);
            views.setFloat(R.id.arrow, "setAlpha", alpha);
            Intent open = new Intent(context, MainActivity.class);
            views.setOnClickPendingIntent(R.id.compass_root, PendingIntent.getActivity(
                    context,
                    id,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (full) {
                manager.updateAppWidget(id, views);
            } else {
                manager.partiallyUpdateAppWidget(id, views);
            }
            LAST_ROTATION.put(id, rotation);
            LAST_ALPHA.put(id, alpha);
            LAST_PUSH.put(id, android.os.SystemClock.elapsedRealtime());
        }
    }

    private static boolean shouldPush(int widgetId, float rotation, float alpha) {
        Float previousRotation = LAST_ROTATION.get(widgetId);
        Float previousAlpha = LAST_ALPHA.get(widgetId);
        long previousTime = LAST_PUSH.get(widgetId, 0L);
        if (previousRotation == null || previousAlpha == null) {
            return true;
        }
        if (Math.abs(previousAlpha - alpha) > 0.2f) {
            return true;
        }
        float delta = rotation - previousRotation;
        while (delta > 180f) {
            delta -= 360f;
        }
        while (delta < -180f) {
            delta += 360f;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        return Math.abs(delta) >= 5f && now - previousTime >= 500L;
    }
}
