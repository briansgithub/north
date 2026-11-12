package com.north.compass;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

public class MainActivity extends android.app.Activity implements SensorEventListener, LocationListener, Declination.Listener {
    private static final long LOCATION_INTERVAL_MS = 5 * 60 * 1000L;
    private static final float LOCATION_METERS = 400f;

    private WidgetStore store;
    private SensorManager sensors;
    private LocationManager locations;
    private ImageView arrow;
    private RadioGroup modes;
    private TextView declinationStatus;
    private View locationNote;
    private boolean applying;
    private boolean sensorOn;
    private boolean locating;
    private int locationProfile;
    private float smoothed = Float.NaN;
    private boolean headingOk;
    private Location here;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass);
        store = new WidgetStore(this);
        sensors = getSystemService(SensorManager.class);
        locations = getSystemService(LocationManager.class);
        arrow = findViewById(R.id.arrow);
        arrow.setAlpha(0.35f);
        modes = findViewById(R.id.modes);
        declinationStatus = findViewById(R.id.declination_status);
        locationNote = findViewById(R.id.location_note);
        TextView title = findViewById(R.id.title);
        TextView note = findViewById(R.id.note);
        title.setText(R.string.app_name);
        note.setText(getString(R.string.preview_note) + "\n\n" + getString(R.string.add_hint));
        show(store.previewMode());
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (applying) {
                return;
            }
            store.savePreview(currentMode());
            showExtras();
            if (WidgetStore.needsDeclination(currentMode())) {
                askForLocation();
            }
            publishLastKnown();
            syncLocation();
            paint();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Declination.setListener(this);
        Declination.ensureLoaded(this);
        CompassService.ensureRunning(this);
        Sensor rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotation != null) {
            sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_NORMAL);
            sensorOn = true;
        }
        publishLastKnown();
        syncLocation();
        paint();
    }

    @Override
    protected void onPause() {
        Declination.setListener(null);
        if (sensorOn) {
            sensors.unregisterListener(this);
            sensorOn = false;
        }
        stopLocation();
        headingOk = false;
        smoothed = Float.NaN;
        super.onPause();
    }

    private void show(String mode) {
        applying = true;
        int modeId = R.id.mode_magnetic;
        if (WidgetStore.TRUE.equals(mode)) {
            modeId = R.id.mode_true;
        } else if (WidgetStore.GRID.equals(mode)) {
            modeId = R.id.mode_grid;
        }
        modes.check(modeId);
        applying = false;
        showExtras();
    }

    private void showExtras() {
        boolean needs = WidgetStore.needsDeclination(currentMode());
        declinationStatus.setVisibility(needs ? View.VISIBLE : View.GONE);
        locationNote.setVisibility(needs ? View.VISIBLE : View.GONE);
        if (needs) {
            declinationStatus.setText(Declination.status(this));
        }
    }

    private String currentMode() {
        int id = modes.getCheckedRadioButtonId();
        if (id == R.id.mode_true) {
            return WidgetStore.TRUE;
        }
        if (id == R.id.mode_grid) {
            return WidgetStore.GRID;
        }
        return WidgetStore.MAGNETIC;
    }

    private void askForLocation() {
        if (CompassService.hasLocationPermission(this)) {
            maybeAskNotifications();
            return;
        }
        requestPermissions(new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, 1);
    }

    private void maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        publishLastKnown();
        syncLocation();
        paint();
    }

    private void publishLastKnown() {
        Location known = null;
        if (Build.VERSION.SDK_INT >= 31 && locations != null) {
            known = safeLast(LocationManager.FUSED_PROVIDER);
        }
        Location gps = safeLast(LocationManager.GPS_PROVIDER);
        if (known == null || (gps != null && gps.getTime() > known.getTime())) {
            known = gps;
        }
        if (known != null) {
            here = known;
            Declination.note(this, known);
        }
    }

    private Location safeLast(String provider) {
        if (locations == null) {
            return null;
        }
        try {
            return locations.getLastKnownLocation(provider);
        } catch (Exception error) {
            return null;
        }
    }

    private void syncLocation() {
        int profile = 0;
        if (CompassService.hasLocationPermission(this)) {
            if (WidgetStore.GRID.equals(currentMode())) {
                profile = 2;
            } else if (WidgetStore.TRUE.equals(currentMode()) && !Declination.fresh(here)) {
                profile = 1;
            }
        }
        if (profile == locationProfile && (profile == 0 || locating)) {
            return;
        }
        stopLocation();
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
        locating = fused;
    }

    private boolean request(String provider, long intervalMs, float meters) {
        try {
            locations.requestLocationUpdates(provider, intervalMs, meters, this);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void stopLocation() {
        if (locations != null && locating) {
            locations.removeUpdates(this);
        }
        locating = false;
        locationProfile = 0;
    }

    private void paint() {
        showExtras();
        String mode = currentMode();
        if (!headingOk || (WidgetStore.needsDeclination(mode) && !Declination.known())) {
            arrow.setAlpha(0.35f);
            arrow.setRotation(0f);
            return;
        }
        float uptown = Float.NaN;
        if (here != null) {
            uptown = Avenues.uptownTrue(here.getLatitude(), here.getLongitude());
        }
        arrow.setAlpha(1f);
        arrow.setRotation(Heading.arrowRotation(smoothed, mode, Declination.east(), uptown));
    }

    @Override
    public void onDeclination() {
        paint();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float heading = Heading.fromRotationVector(event.values);
        if (Float.isNaN(heading)) {
            return;
        }
        smoothed = Heading.smooth(smoothed, heading);
        headingOk = true;
        paint();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onLocationChanged(Location location) {
        here = location;
        Declination.note(this, location);
        if (locationProfile == 1) {
            stopLocation();
        }
        paint();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}
