package com.smithdtyler.prettygoodmusicplayer.playback;

import android.media.AudioManager;
import android.util.Log;

import com.smithdtyler.prettygoodmusicplayer.playback.Jukebox.PlaybackState;

public class AudioFocusListener implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "AudioFocus";
	private static final int resumeThreshold = 30000;
    private final Jukebox musicPlayer;

    private PlaybackState stateOnFocusLoss = Jukebox.PlaybackState.STOPPED;
    public long audioFocusLossTime = 0;

    AudioFocusListener(Jukebox musicPlayer) {
        this.musicPlayer = musicPlayer;
    }

	public void onAudioFocusChange(int focusChange) {
		Log.w(TAG, "Focus change received " + focusChange);
		if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
			resume("AUDIOFOCUS_GAIN");
		} else if (focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
			resume("AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK");
		} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
			pause("AUDIOFOCUS_LOSS_TRANSIENT");
		} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
			pause("AUDIOFOCUS_LOSS");
		} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
			pause("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
		}
	}

	private void resume(String reason) {
		Log.i(TAG, reason);
		// If it's been less than a certain time, resume playback
		long now = System.currentTimeMillis();
		if (((now - audioFocusLossTime) < resumeThreshold)
				&& stateOnFocusLoss == PlaybackState.PLAYING) {
			musicPlayer.play();
		} else {
			Log.i(TAG, "It's been more than " + resumeThreshold / 1000 +
							" seconds since we were playing, we don't resume playback");
		}
	}

	private void pause(String reason) {
		Log.i(TAG, reason);
		stateOnFocusLoss = musicPlayer.getPlayBackState();
		musicPlayer.pause();
		audioFocusLossTime = System.currentTimeMillis();
	}
}
