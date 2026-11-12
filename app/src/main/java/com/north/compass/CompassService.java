package com.north.compass;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

public class CompassService extends Service implements SensorEventListener, LocationListener {
    private static final String CHANNEL = "compass";
    private static final int NOTIFICATION_ID = 48;
    private static final long LOCATION_INTERVAL_MS = 5 * 60 * 1000L;
    private static final float LOCATION_METERS = 400f;
    private static final int SPECIAL_USE = 1073741824;

    private static volatile Location lastLocation;
    private static volatile float smoothedHeading = Float.NaN;
    private static volatile boolean headingValid;

    private HandlerThread thread;
    private SensorManager sensors;
    private LocationManager locations;
    private boolean screenOn = true;
    private boolean listening;
    private boolean sensorListening;
    private boolean locationListening;
    private int locationProfile;

    static Location location() {
        return lastLocation;
    }

    static boolean hasHeading() {
        return headingValid && !Float.isNaN(smoothedHeading);
    }

    static float heading() {
        return smoothedHeading;
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(
                new android.content.ComponentName(context, CompassWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    static boolean hasLocationPermission(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void ensureRunning(Context context) {
        if (!hasWidgets(context)) {
            return;
        }
        Intent intent = new Intent(context, CompassService.class);
        context.startForegroundService(intent);
    }

    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            if (screenOn) {
                startListening();
            } else {
                stopListening();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, getString(R.string.compass_channel), NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notifications.createNotificationChannel(channel);
        thread = new HandlerThread("north-compass");
        thread.start();
        sensors = getSystemService(SensorManager.class);
        locations = getSystemService(LocationManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        screenOn = power == null || power.isInteractive();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        publishLastKnown();
        if (!promote()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasWidgets(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Declination.note(this, lastLocation);
        if (screenOn) {
            if (!listening) {
                startListening();
            } else {
                syncListeners();
            }
        } else {
            stopListening();
        }
        CompassWidgets.render(this, true);
        return START_STICKY;
    }

    private boolean promote() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_compass)
                .setContentTitle(getString(R.string.compass_notif))
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification notification = builder.build();
        boolean locate = locationProfileWanted() != 0;
        try {
            startInForeground(notification, locate);
            return true;
        } catch (Exception error) {
            if (!locate && hasLocationPermission(this)) {
                try {
                    startInForeground(notification, true);
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        }
    }

    private void startInForeground(Notification notification, boolean locate) {
        if (Build.VERSION.SDK_INT >= 34) {
            int type = locate
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    : SPECIAL_USE;
            startForeground(NOTIFICATION_ID, notification, type);
        } else if (locate) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean anyMode(String mode) {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        int[] ids = manager.getAppWidgetIds(new android.content.ComponentName(this, CompassWidgetProvider.class));
        WidgetStore store = new WidgetStore(this);
        for (int id : ids) {
            if (mode.equals(store.mode(id))) {
                return true;
            }
        }
        return false;
    }

    private int locationProfileWanted() {
        if (!hasLocationPermission(this)) {
            return 0;
        }
        Declination.ensureLoaded(this);
        if (anyMode(WidgetStore.GRID)) {
            return 2;
        }
        if (anyMode(WidgetStore.TRUE) && !Declination.fresh(lastLocation)) {
            return 1;
        }
        return 0;
    }

    private void publishLastKnown() {
        Location known = null;
        if (Build.VERSION.SDK_INT >= 31) {
            known = safeLast(LocationManager.FUSED_PROVIDER);
        }
        known = newest(known, safeLast(LocationManager.GPS_PROVIDER));
        if (known != null) {
            lastLocation = known;
        }
        Declination.ensureLoaded(this);
    }

    private Location safeLast(String provider) {
        try {
            return locations.getLastKnownLocation(provider);
        } catch (Exception error) {
            return null;
        }
    }

    private static Location newest(Location first, Location second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.getTime() > first.getTime() ? second : first;
    }

    private boolean request(String provider, long intervalMs, float meters) {
        try {
            locations.requestLocationUpdates(provider, intervalMs, meters, this, thread.getLooper());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        syncListeners();
    }

    private void syncListeners() {
        if (!listening || !screenOn) {
            return;
        }
        syncSensor();
        syncLocation();
    }

    private void syncSensor() {
        if (sensors == null) {
            return;
        }
        if (!sensorListening) {
            Sensor rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotation != null) {
                sensors.registerListener(
                        this, rotation, SensorManager.SENSOR_DELAY_NORMAL, new Handler(thread.getLooper()));
                sensorListening = true;
            }
        }
    }

    private void syncLocation() {
        int profile = locationProfileWanted();
        if (profile == locationProfile && (profile == 0 || locationListening)) {
            return;
        }
        if (locationListening && locations != null) {
            locations.removeUpdates(this);
            locationListening = false;
        }
        locationProfile = profile;
        if (profile == 0 || locations == null) {
            return;
        }
        long interval = profile == 2 ? LOCATION_INTERVAL_MS : 0L;
        float meters = profile == 2 ? LOCATION_METERS : 0f;
        boolean fused = false;
        if (Build.VERSION.SDK_INT >= 31) {
            fused = request(LocationManager.FUSED_PROVIDER, interval, meters);
        }
        if (!fused) {
            fused = request(LocationManager.GPS_PROVIDER, interval, meters);
        }
        locationListening = fused;
    }

    private void stopListening() {
        if (!listening) {
            return;
        }
        if (locations != null) {
            locations.removeUpdates(this);
        }
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        sensorListening = false;
        locationListening = false;
        locationProfile = 0;
        listening = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        lastLocation = location;
        Declination.note(this, location);
        if (locationProfile == 1 && locations != null) {
            locations.removeUpdates(this);
            locationListening = false;
            locationProfile = 0;
        }
        CompassWidgets.render(this, false);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }
        float heading = Heading.fromRotationVector(event.values);
        if (Float.isNaN(heading)) {
            return;
        }
        smoothedHeading = Heading.smooth(smoothedHeading, heading);
        headingValid = true;
        CompassWidgets.render(this, false);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        stopListening();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
            // Already unregistered.
        }
        if (thread != null) {
            thread.quitSafely();
        }
        headingValid = false;
        smoothedHeading = Float.NaN;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
