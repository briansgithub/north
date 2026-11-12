package com.north.compass;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class Declination {
    interface Listener {
        void onDeclination();
    }

    private static final double REFRESH_MILES = 20.0;
    private static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long RETRY_MS = 10L * 60 * 1000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile float east = Float.NaN;
    private static volatile boolean failed;
    private static boolean loaded;
    private static double cachedLat;
    private static double cachedLon;
    private static long cachedAt;
    private static long retryAfter;
    private static Listener listener;

    private Declination() {}

    static void setListener(Listener next) {
        listener = next;
    }

    static float east() {
        return east;
    }

    static boolean known() {
        return !Float.isNaN(east);
    }

    static void ensureLoaded(Context context) {
        load(context.getApplicationContext());
    }

    static String status(Context context) {
        ensureLoaded(context);
        if (!Float.isNaN(east)) {
            String side = east < 0f
                    ? context.getString(R.string.side_west)
                    : context.getString(R.string.side_east);
            return context.getString(R.string.declination_value, Math.abs(east), side);
        }
        if (failed) {
            return context.getString(R.string.declination_unavailable);
        }
        return context.getString(R.string.declination_looking);
    }

    static boolean fresh(Location location) {
        if (location == null || Float.isNaN(east) || cachedAt == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - cachedAt >= REFRESH_MS) {
            return false;
        }
        return miles(cachedLat, cachedLon, location.getLatitude(), location.getLongitude()) < REFRESH_MILES;
    }

    static void note(Context context, Location location) {
        if (location == null) {
            return;
        }
        Context app = context.getApplicationContext();
        load(app);
        if (fresh(location) || System.currentTimeMillis() < retryAfter) {
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        EXECUTOR.execute(() -> {
            try {
                float value = fetch(lat, lon);
                if (Float.isNaN(value)) {
                    failed = Float.isNaN(east);
                    retryAfter = System.currentTimeMillis() + RETRY_MS;
                } else {
                    save(app, lat, lon, value);
                    east = value;
                    failed = false;
                    retryAfter = 0L;
                    CompassWidgets.render(app, false);
                }
                Listener current = listener;
                if (current != null) {
                    MAIN.post(current::onDeclination);
                }
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static void load(Context app) {
        if (loaded) {
            return;
        }
        SharedPreferences prefs = app.getSharedPreferences("north_compass", Context.MODE_PRIVATE);
        if (prefs.contains("declination")) {
            east = prefs.getFloat("declination", Float.NaN);
            cachedLat = Double.longBitsToDouble(prefs.getLong("declination_lat", 0L));
            cachedLon = Double.longBitsToDouble(prefs.getLong("declination_lon", 0L));
            cachedAt = prefs.getLong("declination_at", 0L);
        }
        loaded = true;
    }

    private static void save(Context app, double lat, double lon, float value) {
        cachedLat = lat;
        cachedLon = lon;
        cachedAt = System.currentTimeMillis();
        loaded = true;
        app.getSharedPreferences("north_compass", Context.MODE_PRIVATE)
                .edit()
                .putFloat("declination", value)
                .putLong("declination_lat", Double.doubleToRawLongBits(lat))
                .putLong("declination_lon", Double.doubleToRawLongBits(lon))
                .putLong("declination_at", cachedAt)
                .apply();
    }

    private static float fetch(double lat, double lon) {
        HttpURLConnection connection = null;
        try {
            String address = "https://www.ngdc.noaa.gov/geomag-web/calculators/calculateDeclination"
                    + "?lat1=" + format(lat)
                    + "&lon1=" + format(lon)
                    + "&model=WMM&resultFormat=json&key=zNEw7";
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", "NorthCompass/1.0");
            if (connection.getResponseCode() != 200) {
                return Float.NaN;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] chunk = new byte[2048];
                int count;
                while ((count = input.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, count);
                }
            }
            JSONObject result = new JSONObject(buffer.toString("UTF-8"))
                    .getJSONArray("result")
                    .getJSONObject(0);
            return (float) result.getDouble("declination");
        } catch (Exception ignored) {
            return Float.NaN;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private static double miles(double lat1, double lon1, double lat2, double lon2) {
        double radius = 3958.8;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dlat = p2 - p1;
        double dlon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dlat / 2) * Math.sin(dlat / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dlon / 2) * Math.sin(dlon / 2);
        return 2 * radius * Math.asin(Math.sqrt(h));
    }
}
