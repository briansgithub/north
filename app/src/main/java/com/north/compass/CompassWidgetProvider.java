package com.north.compass;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

public class CompassWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        CompassService.ensureRunning(context);
        CompassWidgets.render(context, true);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        WidgetStore store = new WidgetStore(context);
        for (int id : appWidgetIds) {
            store.delete(id);
            CompassWidgets.forget(id);
        }
        if (!CompassService.hasWidgets(context)) {
            context.stopService(new Intent(context, CompassService.class));
        }
    }

    @Override
    public void onDisabled(Context context) {
        context.stopService(new Intent(context, CompassService.class));
    }
}
