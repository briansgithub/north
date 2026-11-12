package com.north.compass;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

public class ConfigureActivity extends Activity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private WidgetStore store;
    private RadioGroup modes;
    private TextView declinationStatus;
    private View locationNote;
    private boolean applying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        setContentView(R.layout.activity_compass);
        store = new WidgetStore(this);
        findViewById(R.id.arrow).setVisibility(View.GONE);
        TextView title = findViewById(R.id.title);
        TextView note = findViewById(R.id.note);
        title.setText(R.string.configure_title);
        note.setText(R.string.widget_desc);
        modes = findViewById(R.id.modes);
        declinationStatus = findViewById(R.id.declination_status);
        locationNote = findViewById(R.id.location_note);
        Button save = findViewById(R.id.save);
        save.setVisibility(View.VISIBLE);
        show(store.mode(widgetId));
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (!applying) {
                showExtras();
            }
        });
        save.setOnClickListener(v -> {
            if (WidgetStore.needsDeclination(currentMode()) && !CompassService.hasLocationPermission(this)) {
                requestPermissions(new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, 1);
                return;
            }
            commit();
        });
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

    private void commit() {
        store.save(widgetId, currentMode());
        CompassService.ensureRunning(this);
        CompassWidgets.render(this, true);
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            commit();
        }
    }
}
