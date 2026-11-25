package com.aktivesolutions.rolodex;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Onboarding / control screen: grant the overlay permission and start or stop the service. */
public class MainActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 1001;

    private TextView status;
    private Button btnPermission;
    private Button btnToggle;
    private Spinner sideSpinner;
    private Spinner launchSpinner;
    private SeekBar widthSeek;
    private SeekBar heightSeek;
    private TextView widthValue;
    private TextView heightValue;
    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        btnPermission = findViewById(R.id.btn_permission);
        btnToggle = findViewById(R.id.btn_toggle);
        sideSpinner = findViewById(R.id.side_spinner);
        launchSpinner = findViewById(R.id.launch_spinner);
        widthSeek = findViewById(R.id.width_seek);
        heightSeek = findViewById(R.id.height_seek);
        widthValue = findViewById(R.id.width_value);
        heightValue = findViewById(R.id.height_value);
        prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);

        String side = prefs.getString(OverlayService.KEY_SIDE, OverlayService.SIDE_RIGHT);
        sideSpinner.setSelection(
                OverlayService.SIDE_LEFT.equals(side) ? 1 : 0);
        sideSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                String newSide = position == 1
                        ? OverlayService.SIDE_LEFT : OverlayService.SIDE_RIGHT;
                prefs.edit().putString(OverlayService.KEY_SIDE, newSide).apply();
                OverlayService.onTriggerPrefsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        String launchMode = prefs.getString(
                OverlayService.KEY_LAUNCH_MODE, OverlayService.LAUNCH_FULL);
        int launchIndex = OverlayService.LAUNCH_SPLIT.equals(launchMode) ? 1
                : OverlayService.LAUNCH_FLOAT.equals(launchMode) ? 2 : 0;
        launchSpinner.setSelection(launchIndex);
        launchSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                String mode = position == 1 ? OverlayService.LAUNCH_SPLIT
                        : position == 2 ? OverlayService.LAUNCH_FLOAT
                        : OverlayService.LAUNCH_FULL;
                prefs.edit().putString(OverlayService.KEY_LAUNCH_MODE, mode).apply();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        int width = prefs.getInt(OverlayService.KEY_WIDTH, OverlayService.DEFAULT_WIDTH);
        int height = prefs.getInt(OverlayService.KEY_HEIGHT, OverlayService.DEFAULT_HEIGHT);
        widthSeek.setProgress(width - 6);
        heightSeek.setProgress(height - 60);
        widthValue.setText(width + " dp");
        heightValue.setText(height + " dp");

        widthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = 6 + progress;
                widthValue.setText(value + " dp");
                prefs.edit().putInt(OverlayService.KEY_WIDTH, value).apply();
                OverlayService.onTriggerPrefsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        heightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int value = 60 + progress;
                heightValue.setText(value + " dp");
                prefs.edit().putInt(OverlayService.KEY_HEIGHT, value).apply();
                OverlayService.onTriggerPrefsChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        btnPermission.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        btnToggle.setOnClickListener(v -> {
            if (OverlayService.running) {
                stopService(new Intent(this, OverlayService.class));
            } else {
                Intent intent = new Intent(this, OverlayService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }
            handler.postDelayed(this::refresh, 350);
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean granted = Settings.canDrawOverlays(this);
        if (granted) {
            status.setText("Overlay permission: GRANTED\nService: "
                    + (OverlayService.running ? "RUNNING" : "stopped"));
        } else {
            status.setText("Overlay permission: NOT GRANTED — grant it, then start Rolodex");
        }
        btnPermission.setEnabled(!granted);
        btnToggle.setEnabled(granted);
        btnToggle.setText(OverlayService.running ? "Stop Rolodex" : "Start Rolodex");
    }
}
