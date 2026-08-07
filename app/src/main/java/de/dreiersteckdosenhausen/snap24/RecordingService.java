package de.dreiersteckdosenhausen.snap24;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import android.os.StatFs;

public class RecordingService extends Service {

    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "recording_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int DEFAULT_SEGMENT_DURATION_MS = 30 * 60 * 1000; // 30 minutes
    private static final int MAX_SEGMENTS = 48; // 24 hours worth

    private static final int DEFAULT_VIDEO_BITRATE_KBPS = 8000; // 8 Mbps

    private MediaRecorder mediaRecorder;
    private Camera camera;
    private PowerManager.WakeLock wakeLock;
    private Timer segmentTimer;
    private Timer storageCheckTimer;
    private int currentSegmentIndex = 0;
    private File[] segmentFiles = new File[MAX_SEGMENTS];
    private boolean isRecording = false;
    private File recordingsDir;
    private int cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummySurface;
    private int videoQuality = CamcorderProfile.QUALITY_HIGH;
    private int segmentDurationMs = DEFAULT_SEGMENT_DURATION_MS;
    private int videoBitrateKbps = DEFAULT_VIDEO_BITRATE_KBPS;

    // Use Application context for proper locale handling
    private Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        createNotificationChannel();
        acquireWakeLock();
        initRecordingsDirectory();
        // Create dummy surface texture for MediaRecorder preview
        dummySurfaceTexture = new SurfaceTexture(0);
        dummySurface = new Surface(dummySurfaceTexture);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("STOP_RECORDING".equals(intent.getAction())) {
                stopRecording();
                stopSelf();
                return START_NOT_STICKY;
            }
            // Read quality, segment duration, camera facing, and bitrate from intent extras
            videoQuality = intent.getIntExtra("video_quality", CamcorderProfile.QUALITY_HIGH);
            int segmentDurationMin = intent.getIntExtra("segment_duration_min", 30);
            segmentDurationMs = segmentDurationMin * 60 * 1000;
            cameraId = intent.getIntExtra("camera_facing", Camera.CameraInfo.CAMERA_FACING_BACK);
            videoBitrateKbps = intent.getIntExtra("video_bitrate_kbps", DEFAULT_VIDEO_BITRATE_KBPS);
            Log.i(TAG, "Starting with quality: " + videoQuality + ", segment duration: " + segmentDurationMin + " min, camera: " + cameraId + ", bitrate: " + videoBitrateKbps + "kbps");
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        startRecording();
        scheduleStorageCheck();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(appContext.getString(R.string.notification_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent appIntent = new Intent(this, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent appPendingIntent = PendingIntent.getActivity(
                this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.notification_title))
                .setContentText(appContext.getString(R.string.notification_text, currentSegmentIndex + 1, MAX_SEGMENTS))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(appPendingIntent)
                .build();
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SNAP24::RecordingWakeLock");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void initRecordingsDirectory() {
        File externalDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        recordingsDir = new File(externalDir, "SNAP24");
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs();
        }
        // Initialize segment index and file array from existing files
        initializeSegmentState();
    }

    private void initializeSegmentState() {
        File[] files = recordingsDir.listFiles((dir, name) -> name.startsWith("SNAP24_") && name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            currentSegmentIndex = 0;
            return;
        }

        // Parse segment indices from filenames: SNAP24_XX_timestamp.mp4
        int maxIndex = -1;
        for (File f : files) {
            String name = f.getName();
            try {
                // Format: SNAP24_00_20260807_081932.mp4
                String[] parts = name.split("_");
                if (parts.length >= 2) {
                    int idx = Integer.parseInt(parts[1]);
                    segmentFiles[idx] = f;
                    if (idx > maxIndex) maxIndex = idx;
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Could not parse segment index from: " + name);
            }
        }

        // Next segment index wraps after MAX_SEGMENTS
        currentSegmentIndex = (maxIndex + 1) % MAX_SEGMENTS;
        Log.i(TAG, "Initialized: currentSegmentIndex=" + currentSegmentIndex + ", found " + files.length + " existing files");
    }

    private void startRecording() {
        if (isRecording) return;

        if (checkPermissions()) {
            initCamera();
            if (camera != null) {
                startSegmentRecording();
                scheduleSegmentRotation();
            }
        }
    }

    private boolean checkPermissions() {
        return checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void initCamera() {
        try {
            camera = Camera.open(cameraId);
            if (camera == null) {
                Log.e(TAG, "Failed to open camera");
                return;
            }

            Camera.Parameters params = camera.getParameters();

            // Set focus mode for continuous video
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            params.setRecordingHint(true);

            // Use camcorder profile for best quality
            CamcorderProfile profile = CamcorderProfile.get(cameraId, videoQuality);
            if (profile != null) {
                params.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
            }

            camera.setParameters(params);
            Log.i(TAG, "Camera initialized with quality " + videoQuality + ": " + (profile != null ? profile.videoFrameWidth + "x" + profile.videoFrameHeight : "none"));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            if (camera != null) {
                camera.release();
                camera = null;
            }
        }
    }

    private void startSegmentRecording() {
        if (mediaRecorder != null) {
            stopSegmentRecording();
        }

        if (camera == null) {
            Log.e(TAG, "Camera is null, cannot start recording");
            return;
        }

        File outputFile = getNextSegmentFile();
        segmentFiles[currentSegmentIndex] = outputFile;

        mediaRecorder = new MediaRecorder();

        try {
            camera.unlock();
            mediaRecorder.setCamera(camera);

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            // Use camcorder profile for proper configuration
            CamcorderProfile profile = CamcorderProfile.get(cameraId, videoQuality);
            if (profile != null) {
                mediaRecorder.setProfile(profile);
                // Override bitrate with user-selected value
                mediaRecorder.setVideoEncodingBitRate(videoBitrateKbps * 1000);
                Log.i(TAG, "Using camcorder profile: " + profile.videoFrameWidth + "x" + profile.videoFrameHeight +
                    " format=" + profile.fileFormat + " vcodec=" + profile.videoCodec + " acodec=" + profile.audioCodec +
                    " profile bitrate=" + profile.videoBitRate + " -> overridden to " + videoBitrateKbps + "kbps");
            } else {
                // Fallback manual configuration
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                mediaRecorder.setVideoEncodingBitRate(videoBitrateKbps * 1000);
                mediaRecorder.setVideoFrameRate(30);
                mediaRecorder.setVideoSize(1920, 1080);
                Log.i(TAG, "Using fallback manual configuration with bitrate: " + videoBitrateKbps + "kbps");
            }

            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

            // Set preview display (required for MediaRecorder on some devices)
            try {
                mediaRecorder.setPreviewDisplay(dummySurface);
            } catch (Exception e) {
                Log.w(TAG, "Could not set preview display", e);
            }

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            updateNotification(appContext.getString(R.string.notification_text, currentSegmentIndex + 1, MAX_SEGMENTS));
            Log.i(TAG, "Started recording: " + outputFile.getName());
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Failed to start recording", e);
            isRecording = false;
            releaseMediaRecorder();
        }
    }

    private void stopSegmentRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                Log.i(TAG, "Stopped recording segment");
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaRecorder stop failed", e);
            }
            releaseMediaRecorder();
            isRecording = false;
        }
        if (camera != null) {
            try {
                camera.reconnect();
            } catch (IOException e) {
                Log.w(TAG, "Camera reconnect failed", e);
            }
        }
    }

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private File getNextSegmentFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = String.format(Locale.getDefault(), "SNAP24_%02d_%s.mp4", currentSegmentIndex, timestamp);
        return new File(recordingsDir, fileName);
    }

    private void scheduleSegmentRotation() {
        if (segmentTimer != null) {
            segmentTimer.cancel();
        }
        segmentTimer = new Timer();
        segmentTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                rotateSegment();
            }
        }, segmentDurationMs, segmentDurationMs);
    }

    private void rotateSegment() {
        stopSegmentRecording();
        currentSegmentIndex = (currentSegmentIndex + 1) % MAX_SEGMENTS;
        // Delete old file at this index (if exists) to maintain true ring buffer
        File oldFile = segmentFiles[currentSegmentIndex];
        if (oldFile != null && oldFile.exists()) {
            boolean deleted = oldFile.delete();
            Log.i(TAG, "Deleted old segment file: " + oldFile.getName() + " (success=" + deleted + ")");
        }
        startSegmentRecording();
        updateNotification(appContext.getString(R.string.notification_text, currentSegmentIndex + 1, MAX_SEGMENTS));
        Log.i(TAG, "Rotated to segment index: " + currentSegmentIndex);
    }

    private void scheduleStorageCheck() {
        if (storageCheckTimer != null) {
            storageCheckTimer.cancel();
        }
        storageCheckTimer = new Timer();
        storageCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkStorageAndStopIfFull();
            }
        }, 60000, 60000); // Check every minute
    }

    private void checkStorageAndStopIfFull() {
        try {
            File recordingsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SNAP24");
            StatFs stat = new StatFs(recordingsDir.getPath());
            long freeBytes = stat.getAvailableBytes();
            // Stop if less than 100MB free
            if (freeBytes < 100 * 1024 * 1024) {
                Log.w(TAG, "Storage low (" + freeBytes + " bytes free), stopping recording");
                stopRecording();
                stopSelf();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking storage", e);
        }
    }

    private void updateNotification(String text) {
        Intent appIntent = new Intent(this, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent appPendingIntent = PendingIntent.getActivity(
                this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(appContext.getString(R.string.notification_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(appPendingIntent)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void stopRecording() {
        if (segmentTimer != null) {
            segmentTimer.cancel();
            segmentTimer = null;
        }
        if (storageCheckTimer != null) {
            storageCheckTimer.cancel();
            storageCheckTimer = null;
        }
        stopSegmentRecording();
        if (camera != null) {
            camera.release();
            camera = null;
        }
        releaseWakeLock();
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        if (dummySurface != null) {
            dummySurface.release();
        }
        if (dummySurfaceTexture != null) {
            dummySurfaceTexture.release();
        }
        super.onDestroy();
    }
}