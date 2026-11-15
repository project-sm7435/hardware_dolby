package org.lineageos.dspvolume.xiaomi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

public class VolumeListenerReceiver extends BroadcastReceiver {

    private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";

    /** Tells the DSP the current media volume index. */
    static void pushVolume(AudioManager audioManager, int index) {
        if (audioManager == null || index < 0) {
            return;
        }
        audioManager.setParameters("volume_change=" + index + ";flags=8");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) {
            return;
        }
        pushVolume(context.getSystemService(AudioManager.class),
                intent.getIntExtra(EXTRA_STREAM_VALUE, -1));
    }
}
