package org.lineageos.dspvolume.xiaomi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

public class VolumeListenerService extends Service {

    private static final long DEVICE_SYNC_DELAY_MS = 500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final VolumeListenerReceiver mVolumeReceiver = new VolumeListenerReceiver();
    private final Runnable mSyncRunnable = this::syncVolume;
    private AudioManager mAudioManager;
    private boolean mReceiverRegistered;
    private boolean mCallbackRegistered;

    // The volume index can change with the output route without a VOLUME_CHANGED_ACTION,
    // so the DSP is told again after the route settled.
    private final AudioDeviceCallback mDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            scheduleSync();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            scheduleSync();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Registered once here (and removed in onDestroy). Doing it in onStartCommand added one more
    // receiver for every startService call, so each volume change reached the DSP several times.
    @Override
    public void onCreate() {
        super.onCreate();
        mAudioManager = getSystemService(AudioManager.class);

        registerReceiver(mVolumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                Context.RECEIVER_NOT_EXPORTED);
        mReceiverRegistered = true;

        mAudioManager.registerAudioDeviceCallback(mDeviceCallback, mHandler);
        mCallbackRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncVolume();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mSyncRunnable);
        if (mCallbackRegistered) {
            mAudioManager.unregisterAudioDeviceCallback(mDeviceCallback);
            mCallbackRegistered = false;
        }
        if (mReceiverRegistered) {
            unregisterReceiver(mVolumeReceiver);
            mReceiverRegistered = false;
        }
        super.onDestroy();
    }

    private void scheduleSync() {
        mHandler.removeCallbacks(mSyncRunnable);
        mHandler.postDelayed(mSyncRunnable, DEVICE_SYNC_DELAY_MS);
    }

    private void syncVolume() {
        VolumeListenerReceiver.pushVolume(mAudioManager,
                mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }
}
