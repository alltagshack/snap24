package de.dreiersteckdosenhausen.snap24;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int IGNORE_BATTERY_OPTIMIZATION_REQUEST = 101;
    private static final String PREFS_NAME = "Snap24Prefs";
    private static final String KEY_VIDEO_QUALITY_BACK = "video_quality_back";
    private static final String KEY_VIDEO_QUALITY_FRONT = "video_quality_front";
    private static final String KEY_SEGMENT_DURATION = "segment_duration_min";
    private static final String KEY_CAMERA_FACING = "camera_facing";
    private static final String KEY_VIDEO_BITRATE = "video_bitrate_kbps";

    private Button btnStartRecording;
    private Button btnStopRecording;
    private Button btnOpenRecordingsFolder;
    private TextView tvStatus;
    private TextView tvInfo;

    private boolean isRecording = false;
    private int selectedQualityBack = CamcorderProfile.QUALITY_HIGH;
    private int selectedQualityFront = CamcorderProfile.QUALITY_HIGH;
    private int segmentDurationMin = 30;
    private int selectedCameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int selectedBitrateKbps = 8000; // default 8 Mbps

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Force locale before super.onCreate to ensure resources are loaded with correct locale
        forceLocale();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Enable app icon in action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setIcon(R.mipmap.ic_launcher);
        }

        loadPreferences();

        btnStartRecording = findViewById(R.id.btnStartRecording);
        btnStopRecording = findViewById(R.id.btnStopRecording);
        btnOpenRecordingsFolder = findViewById(R.id.btnOpenRecordingsFolder);
        tvStatus = findViewById(R.id.tvStatus);
        tvInfo = findViewById(R.id.tvInfo);

        btnStartRecording.setOnClickListener(v -> requestPermissionsAndStart());
        btnStopRecording.setOnClickListener(v -> stopRecording());
        btnOpenRecordingsFolder.setOnClickListener(v -> openRecordingsFolder());

        updateUI();
        checkBatteryOptimization();
    }

    private void forceLocale() {
        Resources resources = getResources();
        Configuration configuration = resources.getConfiguration();
        Locale locale = Locale.getDefault();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(new android.os.LocaleList(locale));
        } else {
            configuration.setLocale(locale);
        }
        
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPreferences();
        checkServiceState();
        updateUI();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_back_camera_quality) {
            showQualityDialog(Camera.CameraInfo.CAMERA_FACING_BACK);
            return true;
        } else if (id == R.id.action_front_camera_quality) {
            showQualityDialog(Camera.CameraInfo.CAMERA_FACING_FRONT);
            return true;
        } else if (id == R.id.action_camera_selection) {
            showCameraSelectionDialog();
            return true;
        } else if (id == R.id.action_video_bitrate) {
            showBitrateDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkServiceState() {
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        isRecording = false;
        for (android.app.ActivityManager.RunningServiceInfo service : services) {
            if (RecordingService.class.getName().equals(service.service.getClassName())) {
                isRecording = true;
                break;
            }
        }
    }

    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        selectedQualityBack = prefs.getInt(KEY_VIDEO_QUALITY_BACK, CamcorderProfile.QUALITY_HIGH);
        selectedQualityFront = prefs.getInt(KEY_VIDEO_QUALITY_FRONT, CamcorderProfile.QUALITY_HIGH);
        segmentDurationMin = prefs.getInt(KEY_SEGMENT_DURATION, 30);
        selectedCameraFacing = prefs.getInt(KEY_CAMERA_FACING, Camera.CameraInfo.CAMERA_FACING_BACK);
        selectedBitrateKbps = prefs.getInt(KEY_VIDEO_BITRATE, 8000);
    }

    private void savePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putInt(KEY_VIDEO_QUALITY_BACK, selectedQualityBack)
                .putInt(KEY_VIDEO_QUALITY_FRONT, selectedQualityFront)
                .putInt(KEY_SEGMENT_DURATION, segmentDurationMin)
                .putInt(KEY_CAMERA_FACING, selectedCameraFacing)
                .putInt(KEY_VIDEO_BITRATE, selectedBitrateKbps)
                .apply();
    }

    private void showQualityDialog(int cameraFacing) {
        // Query device for supported video qualities for this camera
        List<QualityOption> supportedQualities = getSupportedVideoQualities(cameraFacing);
        
        if (supportedQualities.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_qualities), Toast.LENGTH_LONG).show();
            return;
        }

        String[] qualityLabels = new String[supportedQualities.size()];
        int[] qualityValues = new int[supportedQualities.size()];
        
        for (int i = 0; i < supportedQualities.size(); i++) {
            QualityOption opt = supportedQualities.get(i);
            qualityLabels[i] = getString(opt.labelResId);
            qualityValues[i] = opt.quality;
        }

        int currentQuality = (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) ? selectedQualityBack : selectedQualityFront;
        int currentIndex = 0;
        for (int i = 0; i < qualityValues.length; i++) {
            if (qualityValues[i] == currentQuality) {
                currentIndex = i;
                break;
            }
        }

        String title = getString(cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK ? R.string.settings_back_camera_quality : R.string.settings_front_camera_quality);
        
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(qualityLabels, currentIndex, (dialog, which) -> {
                    int newQuality = qualityValues[which];
                    if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        selectedQualityBack = newQuality;
                    } else {
                        selectedQualityFront = newQuality;
                    }
                    savePreferences();
                    updateUI();
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showCameraSelectionDialog() {
        String[] cameraLabels = {getString(R.string.camera_back), getString(R.string.camera_front)};
        int[] cameraValues = {Camera.CameraInfo.CAMERA_FACING_BACK, Camera.CameraInfo.CAMERA_FACING_FRONT};
        int cameraIndex = selectedCameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_camera_selection))
                .setSingleChoiceItems(cameraLabels, cameraIndex, (dialog, which) -> {
                    selectedCameraFacing = cameraValues[which];
                    savePreferences();
                    updateUI();
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showBitrateDialog() {
        String[] bitrateLabels = {
            getString(R.string.bitrate_500),
            getString(R.string.bitrate_1000),
            getString(R.string.bitrate_2000),
            getString(R.string.bitrate_4000),
            getString(R.string.bitrate_6000),
            getString(R.string.bitrate_8000),
            getString(R.string.bitrate_12000),
            getString(R.string.bitrate_15000)
        };
        int[] bitrateValues = {500, 1000, 2000, 4000, 6000, 8000, 12000, 15000};
        int bitrateIndex = 0;
        for (int i = 0; i < bitrateValues.length; i++) {
            if (bitrateValues[i] == selectedBitrateKbps) {
                bitrateIndex = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_video_bitrate))
                .setSingleChoiceItems(bitrateLabels, bitrateIndex, (dialog, which) -> {
                    selectedBitrateKbps = bitrateValues[which];
                    savePreferences();
                    updateUI();
                    dialog.dismiss();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private static class QualityOption {
        int labelResId;
        int quality;
        QualityOption(int labelResId, int quality) {
            this.labelResId = labelResId;
            this.quality = quality;
        }
    }

    private List<QualityOption> getSupportedVideoQualities(int cameraFacing) {
        List<QualityOption> supported = new ArrayList<>();
        int[] allQualities = {
                CamcorderProfile.QUALITY_HIGH,
                CamcorderProfile.QUALITY_1080P,
                CamcorderProfile.QUALITY_720P,
                CamcorderProfile.QUALITY_480P,
                CamcorderProfile.QUALITY_CIF,
                CamcorderProfile.QUALITY_QVGA,
                CamcorderProfile.QUALITY_QCIF
        };
        int[] labelResIds = {
                R.string.quality_high,
                R.string.quality_1080p,
                R.string.quality_720p,
                R.string.quality_480p,
                R.string.quality_cif,
                R.string.quality_qvga,
                R.string.quality_qcif
        };

        for (int i = 0; i < allQualities.length; i++) {
            if (CamcorderProfile.hasProfile(cameraFacing, allQualities[i])) {
                supported.add(new QualityOption(labelResIds[i], allQualities[i]));
            }
        }
        return supported;
    }

    private void requestPermissionsAndStart() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        };

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startRecording();
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startRecording();
            } else {
                Toast.makeText(this, getString(R.string.toast_permissions_required), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                startActivityForResult(intent, IGNORE_BATTERY_OPTIMIZATION_REQUEST);
            }
        }
    }

    private void startRecording() {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        int quality = selectedCameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK ? selectedQualityBack : selectedQualityFront;
        serviceIntent.putExtra("video_quality", quality);
        serviceIntent.putExtra("segment_duration_min", segmentDurationMin);
        serviceIntent.putExtra("camera_facing", selectedCameraFacing);
        serviceIntent.putExtra("video_bitrate_kbps", selectedBitrateKbps);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isRecording = true;
        updateUI();
        String qualityName = getQualityName(quality);
        String cameraName = getString(selectedCameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK ? R.string.camera_back : R.string.camera_front);
        Toast.makeText(this, getString(R.string.toast_recording_started, qualityName, cameraName, selectedBitrateKbps, segmentDurationMin), Toast.LENGTH_LONG).show();
    }

    private String getQualityName(int quality) {
        switch (quality) {
            case CamcorderProfile.QUALITY_HIGH: return "HIGH";
            case CamcorderProfile.QUALITY_1080P: return "1080P";
            case CamcorderProfile.QUALITY_720P: return "720P";
            case CamcorderProfile.QUALITY_480P: return "480P";
            case CamcorderProfile.QUALITY_CIF: return "CIF";
            case CamcorderProfile.QUALITY_QVGA: return "QVGA";
            case CamcorderProfile.QUALITY_QCIF: return "QCIF";
            default: return "HIGH";
        }
    }

    private void stopRecording() {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        serviceIntent.setAction("STOP_RECORDING");
        startService(serviceIntent);
        isRecording = false;
        updateUI();
        Toast.makeText(this, getString(R.string.toast_recording_stopped), Toast.LENGTH_SHORT).show();
    }

    private void openRecordingsFolder() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "SNAP24");
        intent.setDataAndType(android.net.Uri.fromFile(dir), "resource/folder");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.toast_no_file_manager), Toast.LENGTH_SHORT).show();
        }
    }

private void updateUI() {
        String qualityName = getQualityName(selectedCameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK ? selectedQualityBack : selectedQualityFront);
        String cameraName = getString(selectedCameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK ? R.string.camera_back : R.string.camera_front);
        long estimatedSizePerSegmentMB = estimateSizePerSegmentMB(selectedBitrateKbps, segmentDurationMin);
        long estimatedTotalGB = (estimatedSizePerSegmentMB * 48) / 1024;
        String freeSpaceStr = getFreeSpaceStr();

        if (isRecording) {
            btnStartRecording.setEnabled(false);
            btnStartRecording.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E)); // grey
            btnStopRecording.setEnabled(true);
            btnStopRecording.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336)); // red
            tvStatus.setText(getString(R.string.status_recording));
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            tvInfo.setText(
                getString(R.string.info_quality_format, qualityName) + "\n" +
                getString(R.string.info_camera_format, cameraName) + "\n" +
                getString(R.string.info_bitrate_format, selectedBitrateKbps) + "\n" +
                getString(R.string.info_ring_buffer) + "\n" +
                getString(R.string.info_est_size_format, estimatedSizePerSegmentMB) + "\n" +
                getString(R.string.info_est_total_format, estimatedTotalGB) + "\n" +
                getString(R.string.info_free_space_format, freeSpaceStr) + "\n" +
                getString(R.string.status_recording_details)
            );
        } else {
            btnStartRecording.setEnabled(true);
            btnStartRecording.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50)); // green
            btnStopRecording.setEnabled(false);
            btnStopRecording.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9E9E9E)); // grey
            tvStatus.setText(getString(R.string.status_idle));
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            tvInfo.setText(
                getString(R.string.info_quality_format, qualityName) + "\n" +
                getString(R.string.info_camera_format, cameraName) + "\n" +
                getString(R.string.info_bitrate_format, selectedBitrateKbps) + "\n" +
                getString(R.string.info_ring_buffer) + "\n" +
                getString(R.string.info_est_size_format, estimatedSizePerSegmentMB) + "\n" +
                getString(R.string.info_est_total_format, estimatedTotalGB) + "\n" +
                getString(R.string.info_free_space_format, freeSpaceStr) + "\n" +
                getString(R.string.status_idle_details)
            );
        }
    }

    private String getFreeSpaceStr() {
        try {
            java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "SNAP24");
            long freeBytes = dir.getUsableSpace();
            if (freeBytes > 1024 * 1024 * 1024) {
                return String.format("%.1f GB", freeBytes / (1024.0 * 1024 * 1024));
            } else if (freeBytes > 1024 * 1024) {
                return String.format("%.1f MB", freeBytes / (1024.0 * 1024));
            } else {
                return String.format("%.1f KB", freeBytes / 1024.0);
            }
        } catch (Exception e) {
            return "Unknown";
        }
    }

    private long estimateSizePerSegmentMB(int bitrateKbps, int durationMin) {
        // MB = (kbps * 60 * durationMin) / (8 * 1024)
        return (long) (bitrateKbps * 60L * durationMin / (8 * 1024));
    }

}