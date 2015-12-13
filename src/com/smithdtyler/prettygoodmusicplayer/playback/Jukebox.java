package com.smithdtyler.prettygoodmusicplayer.playback;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import ch.blinkenlights.android.vanilla.ReadaheadThread;

public class Jukebox implements MediaPlayer.OnPreparedListener {
	private static final String TAG = "Jukebox";

	public interface PlaybackChangeListener {
		void onPlaybackStateChange();
	}

	private enum STATE {
		IDLE, PREPARED, PLAYING, PAUSED, FINISHED, STOPPED
	}

	public enum PlaybackState {
		PLAYING, PAUSED, STOPPED
	}

	private final MediaPlayer mp;
	private final ReadaheadThread mReadaheadThread;
	private final Context context;
	private final List<PlaybackChangeListener> listeners;
	private final AudioManager audioManager;

	private List<File> playlist;
	private List<Integer> shuffleHead;
	private List<Integer> shuffleTail;

	private FileInputStream currentSongStream;
	private int currentSong;
	private STATE state = STATE.IDLE;

	private int nextPlayCursor = 0;
	private boolean playlistIsShuffled;
	private Random random;
	private AudioFocusListener audioFocusListener;

	public Jukebox(Context context, AudioManager audioManager) {
		this.context = context;
		this.audioManager = audioManager;

		mReadaheadThread = new ReadaheadThread();

		mp = new MediaPlayer();
		mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
		mp.setOnPreparedListener(this);
		mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
			@Override
			public void onCompletion(MediaPlayer mp) {
				Log.i(TAG, "Song complete");
				state = STATE.FINISHED;
				next();
			}

		});

		playlist = new ArrayList<>();
		shuffleHead = new ArrayList<>();
		shuffleTail = new ArrayList<>();

		random = new Random();
		playlistIsShuffled = false;
		listeners = new ArrayList<>();
	}

	public void addPlaybackChangeListener(PlaybackChangeListener listener) {
		listeners.add(listener);
	}

	public void addAudioFocusListener(AudioFocusListener audioFocusListener) {
		this.audioFocusListener = audioFocusListener;
	}

	synchronized void onDestroy() {
		stop();
		reset();
		mp.release();
		Log.i(TAG, "Jukebox Stopped.");
	}

	public synchronized boolean isPlaying() {
		return state != STATE.IDLE && mp.isPlaying();
	}

	public synchronized PlaybackState getPlayBackState() {
		if (state == STATE.PLAYING) {
			return PlaybackState.PLAYING;
		} else if (state == STATE.PAUSED) {
			return PlaybackState.PAUSED;
		} else {
			return PlaybackState.STOPPED;
		}
	}

	public int getSongPlaybackPosition() {
		int position = 0;

		synchronized (mp) {
			if (state == STATE.PLAYING || state == STATE.PAUSED) {
				position = mp.getCurrentPosition();
			}
		}
		return position;
	}
	public synchronized int getSongDuration() {
		int duration = 0;
		if(state != STATE.IDLE) {
			duration = mp.getDuration();
		}
		return duration;
	}

	public synchronized File getCurrentSongFile() {
		return playlist.get(currentSong);
	}

	/**
	 * Called whenever mp finishes preparing and is ready to rock.
	 * @param mp media player
	 */
	@Override
	public void onPrepared(MediaPlayer mp) {
		// Request audio focus for playback
		int request = audioManager.requestAudioFocus(
				audioFocusListener,
				// Use the music stream.
				AudioManager.STREAM_MUSIC,
				// Request permanent focus.
				AudioManager.AUDIOFOCUS_GAIN);
		Log.d(TAG, "requestAudioFocus result = " + request);
		Log.i(TAG, "About to play " + playlist.get(currentSong));

		if (request == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			Log.d(TAG, "We got audio focus!");
			if(nextPlayCursor > 0){
				mp.seekTo(nextPlayCursor);
			}
			mp.start();
			state = STATE.PLAYING;
			onPlaybackChanged();
		} else {
			Log.e(TAG, "Unable to get audio focus");
		}
	}

	public synchronized void setPlaylist(String[] songNames) {
		playlist = new ArrayList<>();
		for(String name: songNames) {
			playlist.add(new File(name));
		}
		resetShuffle();
	}

	public void setPlaylistIndex(int index) {
		currentSong = index;
	}

	synchronized void play() {
		play(0);
	}

	synchronized void play(int position) {
		nextPlayCursor = position;
		if (state != STATE.PLAYING) {
			if(state == STATE.IDLE) {
				loadFile(getCurrentSongFile());
				mp.prepareAsync();
			} else {
				onPrepared(mp);
			}
		}
	}

	synchronized void stop() {
		if (Arrays.asList(STATE.PREPARED, STATE.PLAYING,
				STATE.PAUSED, STATE.FINISHED).contains(state)) {
			mp.stop();
			state = STATE.STOPPED;
			onPlaybackChanged();
		}
	}

    synchronized void seekTo(int position){
        if (Arrays.asList(STATE.PLAYING, STATE.PAUSED,
				STATE.STOPPED, STATE.FINISHED).contains(state)){
            mp.seekTo(position);
        }
    }

    synchronized void playOrPause() {
        if (state == STATE.PLAYING) {
            pause();
        } else if (Arrays.asList(STATE.PAUSED, STATE.FINISHED).contains(state)){
            play();
        }
    }

	/**
	 * Rewind button got pressed, we should rewind x seconds back
	 *
	 * param rewindTime: rewind, time in seconds
	 */
	synchronized void rewind(double rewindTime){
		if (state == STATE.PLAYING || state == STATE.PAUSED) {
			int cursor = getSongPlaybackPosition();
			if (cursor < rewindTime * 1000) {
				seekTo(0);
			} else {
				seekTo(cursor - (int) (rewindTime * 1000));
			}
		}
	}

	/**
	 * Previous button press got received. Depending on the song play time,
	 * we move the playing position to the beginning or load the previous file in the playlist.
	 */
	synchronized void previous() {
		// If there's a song jammin' and it has been jammin' for a long time
		// start the song all over again, baby.
		if (isPlaying()&& getSongPlaybackPosition() > 3000) {
			seekTo(0);
			return;
		}

		if (playlist.size() > 1) {
			// java doesn't like when |-a| < b when doing a % b so we have to do
			// this in order to get a positive value
			currentSong = (((currentSong - 1) % (playlist.size() - 1)) + (playlist.size() - 1)) % (playlist.size() - 1);
		}
		loadFile(playlist.get(currentSong));
		onPlaybackChanged();
	}

	synchronized void next() {
        if(!playlistIsShuffled){
			currentSong = (currentSong + 1) % playlist.size();
        } else {
			currentSong = grabNextShuffledPosition();
        }
		loadFile(playlist.get(currentSong));
		onPlaybackChanged();
    }

	/**
	 * Pause the currently playing song.
	 */
	synchronized void pause() {
		if(state == STATE.PLAYING) {
			mp.pause();
			state = STATE.PAUSED;
			onPlaybackChanged();
		}
	}

	private synchronized void reset() {
		if(state != STATE.IDLE) {
			mp.reset();
			state = STATE.IDLE;
		}
	}

	private synchronized void loadFile(File song) {
		// open the file, pass it into the mp
		stop();
		reset();
		try {
			currentSongStream.close();
		} catch (IOException ignore) {
		}
		try {
			currentSongStream = new FileInputStream(song);
			mp.setDataSource(currentSongStream.getFD());
			mReadaheadThread.setSource(song.getAbsolutePath());
			mp.prepareAsync();
		} catch (FileNotFoundException | IllegalArgumentException | IllegalStateException e) {
			e.printStackTrace();
		} catch (IOException e) {
			// display popup
			String errorText = "Couldn't play file " + song.getAbsolutePath();
			Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show();
		}
	}

	private synchronized void resetShuffle(){
		shuffleHead.clear();
		shuffleTail.clear();
		for(int song = 0; song < playlist.size(); song++){
			shuffleHead.add(song);
		}
	}

	// Props to this fellow: https://stackoverflow.com/questions/5467174/how-to-implement-a-repeating-shuffle-thats-random-but-not-too-random
	private synchronized int grabNextShuffledPosition(){
		int threshold = (int) Math.ceil((playlist.size() + 1) / 2);
		Log.d(TAG, "threshold: " + threshold);
		if(shuffleHead.size() < threshold){
			Log.d(TAG, "Shuffle queue is half empty, adding a new song...");
			if(!shuffleTail.isEmpty()) {
				shuffleHead.add(shuffleTail.get(0));
				shuffleTail.remove(0);
			}
		}

		int rand = Math.abs(random.nextInt()) % shuffleHead.size();
		int loc = shuffleHead.get(rand);
		shuffleHead.remove(rand);
		shuffleTail.add(loc);
		Log.i(TAG, "next position is: " + loc);
		Log.i(TAG, "Front list = " + Arrays.toString(shuffleHead.toArray()));
		Log.i(TAG, "Back list = " + Arrays.toString(shuffleTail.toArray()));
		return loc;
	}

	private void onPlaybackChanged() {
		for(PlaybackChangeListener listener: listeners) {
			listener.onPlaybackStateChange();
		}
	}
}
