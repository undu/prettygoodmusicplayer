package com.smithdtyler.prettygoodmusicplayer.playback;

import android.media.AudioManager;
import android.util.Log;

import com.smithdtyler.prettygoodmusicplayer.playback.MusicPlaybackService.PlaybackState;

public class AudioFocusListener implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "AudioFocus";
    private final MusicPlaybackService playbackService;

    private PlaybackState stateOnFocusLoss = PlaybackState.UNKNOWN;
    public long audioFocusLossTime = 0;

    AudioFocusListener(MusicPlaybackService playbackService) {
        this.playbackService = playbackService;
    }
	public void onAudioFocusChange(int focusChange) {
		Log.w(TAG, "Focus change received " + focusChange);
		if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
			Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
			if (playbackService.isPlaying()) {
				stateOnFocusLoss = PlaybackState.PLAYING;
			} else {
				stateOnFocusLoss = PlaybackState.PAUSED;
			}
            playbackService.pause();
			audioFocusLossTime = System.currentTimeMillis();
			// Pause playback
		} else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
			Log.i(TAG, "AUDIOFOCUS_GAIN");
			// If it's been less than 20 seconds, resume playback
			long curr = System.currentTimeMillis();
			if (((curr - audioFocusLossTime) < 30000)
					&& stateOnFocusLoss == PlaybackState.PLAYING) {
                playbackService.play();
			} else {
				Log.i(TAG,
						"It's been more than 30 seconds or we were paused, don't auto-play");
			}
		} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
			Log.i(TAG, "AUDIOFOCUS_LOSS");
			if (playbackService.isPlaying()) {
				stateOnFocusLoss = PlaybackState.PLAYING;
			} else {
				stateOnFocusLoss = PlaybackState.PAUSED;
			}
            playbackService.pause();
			audioFocusLossTime = System
					.currentTimeMillis();
			// Stop playback
		} else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
			Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK");
			audioFocusLossTime = System.currentTimeMillis();
			if (playbackService.isPlaying()) {
				stateOnFocusLoss = PlaybackState.PLAYING;
			} else {
				stateOnFocusLoss = PlaybackState.PAUSED;
			}
            playbackService.pause();
		} else if (focusChange == AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
			Log.i(TAG, "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK");
			long curr = System.currentTimeMillis();
			if (((curr - this.audioFocusLossTime) < 30000)
					&& stateOnFocusLoss == PlaybackState.PLAYING) {
                playbackService.play();
			} else {
				Log.i(TAG, "It's been more than 30 seconds or we were paused, don't auto-play");
			}
		}
	}
}
