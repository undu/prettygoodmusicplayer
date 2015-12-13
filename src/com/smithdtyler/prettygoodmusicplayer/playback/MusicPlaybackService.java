/**
   The Pretty Good Music Player
   Copyright (C) 2014  Tyler Smith

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.smithdtyler.prettygoodmusicplayer.playback;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.os.*;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.util.Log;

import com.smithdtyler.prettygoodmusicplayer.AlbumList;
import com.smithdtyler.prettygoodmusicplayer.ArtistList;
import com.smithdtyler.prettygoodmusicplayer.NowPlaying;
import com.smithdtyler.prettygoodmusicplayer.R;
import com.smithdtyler.prettygoodmusicplayer.SongList;
import com.smithdtyler.prettygoodmusicplayer.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MusicPlaybackService extends Service implements Jukebox.PlaybackChangeListener {

	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_UNREGISTER_CLIENT = 2;

	// Playback control
	public static final int MSG_PLAYPAUSE = 3;
	public static final int MSG_NEXT = 4;
	public static final int MSG_PREVIOUS = 5;
	public static final int MSG_SET_PLAYLIST = 6;
	public static final int MSG_PAUSE = 7;
	public static final int MSG_PAUSE_IN_ONE_SEC = 8;
	public static final int MSG_CANCEL_PAUSE_IN_ONE_SEC = 9;
	public static final int MSG_TOGGLE_SHUFFLE = 10;
	public static final int MSG_SEEK_TO = 11;
	public static final int MSG_REWIND = 12;
	public static final int MSG_PLAY = 13;

	// State management
	public static final int MSG_REQUEST_STATE = 17;
	public static final int MSG_SERVICE_STATUS = 18;
	public static final int MSG_STOP_SERVICE = 19;

	public static final String PRETTY_SONG_NAME = "PRETTY_SONG_NAME";
	public static final String PRETTY_ARTIST_NAME = "PRETTY_ARTIST_NAME";
	public static final String PRETTY_ALBUM_NAME = "PRETTY_ALBUM_NAME";
	public static final String PLAYBACK_STATE = "PLAYBACK_STATE";
	public static final String TRACK_DURATION = "TRACK_DURATION";
	public static final String TRACK_POSITION = "TRACK_POSITION";
	public static final String IS_SHUFFLING = "IS_SHUFFLING";

	private static final ComponentName cn = new ComponentName(
			MusicBroadcastReceiver.class.getPackage().getName(),
			MusicBroadcastReceiver.class.getName());

	private Timer timer;

	private AudioManager am;
	private ServiceHandler mServiceHandler;
	private static final String TAG = "MusicPlaybackService";
	private static boolean isRunning = false;

	private static int uid = "Music Playback Service".hashCode();

	private OnAudioFocusChangeListener audioFocusListener;

	private static IntentFilter filter = new IntentFilter();
	static {
		filter.addAction(Intent.ACTION_HEADSET_PLUG);
		filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
		filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
	}
	private static MusicBroadcastReceiver receiver = new MusicBroadcastReceiver();

	/**
	 * Keeps track of all current registered clients.
	 */
	List<Messenger> mClients = new ArrayList<>();

	final IncomingHandler mIHandler = new IncomingHandler(this);
	final Messenger mMessenger = new Messenger(mIHandler);

	private AudioManager mAudioManager;

	private long pauseTime = Long.MAX_VALUE;
	private boolean _shuffle = false;
	private String artist;
	private String artistAbsPath;
	private String album;
	private long lastResumeUpdateTime;
	private SharedPreferences sharedPref;
	private HeadphoneBroadcastReceiver headphoneReceiver;

	private Jukebox jukebox;

	// Handler that receives messages from the thread
	private final class ServiceHandler extends Handler {
		public ServiceHandler(Looper looper) {
			super(looper);
		}

		@Override
		public void handleMessage(Message msg) {
			Log.i(TAG, "ServiceHandler got a message!" + msg);
		}
	}

	@Override
	public synchronized void onCreate() {
		Log.i(TAG, "Music Playback Service Created!");

		isRunning = true;
		sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

		jukebox = new Jukebox(getApplicationContext(), (AudioManager) getBaseContext().getSystemService(
				Context.AUDIO_SERVICE));
		jukebox.addPlaybackChangeListener(this);

		mIHandler.addJukebox(jukebox);

		// https://developer.android.com/training/managing-audio/audio-focus.html
		audioFocusListener = new AudioFocusListener(jukebox);
		jukebox.addAudioFocusListener((AudioFocusListener) audioFocusListener);

		// Get permission to play audio
		am = (AudioManager) getBaseContext().getSystemService(
				Context.AUDIO_SERVICE);


		HandlerThread thread = new HandlerThread("PlaybackService", android.os.Process.THREAD_PRIORITY_DEFAULT);
		thread.start();

		// Get the HandlerThread's Looper and use it for our Handler
		mServiceHandler = new ServiceHandler(thread.getLooper());

		/* TODO Notification startup & management
		// https://stackoverflow.com/questions/19474116/the-constructor-notification-is-deprecated
		// https://stackoverflow.com/questions/6406730/updating-an-ongoing-notification-quietly/15538209#15538209
		Intent resultIntent = new Intent(this, NowPlaying.class);
		resultIntent.putExtra("From_Notification", true);
		resultIntent.putExtra(AlbumList.ALBUM_NAME, album);
		resultIntent.putExtra(ArtistList.ARTIST_NAME, artist);
		resultIntent.putExtra(ArtistList.ARTIST_ABS_PATH_NAME, artistAbsPath);

		// Use the FLAG_ACTIVITY_CLEAR_TOP to prevent launching a second
		// NowPlaying if one already exists.
		resultIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);


		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
				resultIntent, 0);

		Builder builder = new NotificationCompat.Builder(
				this.getApplicationContext());

		String contentText = getResources().getString(R.string.ticker_text);
		if (songFile != null) {
			contentText = Utils.getPrettySongName(songFile);
		}

		Notification notification = builder
				.setContentText(contentText)
				.setSmallIcon(R.drawable.ic_pgmp_launcher)
				.setWhen(System.currentTimeMillis())
				.setContentIntent(pendingIntent)
				.setContentTitle(
						getResources().getString(R.string.notification_title))
						.build();

		startForeground(uid, notification);
		*/

		// Timer used to send info to clients (via onTimerTick())
		timer = new Timer();
		timer.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				onTimerTick();
			}
		}, 0, 500L);

		mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		// Apparently audio registration is persistent across lots of things...
		// restarts, installs, etc.
		mAudioManager.registerMediaButtonEventReceiver(cn);
		// I tried to register this in the manifest, but it doesn't seen to
		// accept it, so I'll do it this way.
		getApplicationContext().registerReceiver(receiver, filter);

		Log.i(TAG, "Registering event receiver");
		headphoneReceiver = new HeadphoneBroadcastReceiver();
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction("android.intent.action.HEADSET_PLUG");
		registerReceiver(headphoneReceiver, filter);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i("MyService", "Received start id " + startId + ": " + intent);
		if (intent == null) {
			// intent can be null if this is called by the OS due to
			// "START STICKY"
			// Start, but don't do anything until we get a message from the
			// user.
			return START_STICKY;
		}
		int command = intent.getIntExtra("Message", -1);
		if (command != -1) {
			Log.i(TAG, "I got a message! " + command);
			if (command == MSG_PLAYPAUSE) {
				Log.i(TAG, "I got a playpause message");
				jukebox.playOrPause();
			} else if (command == MSG_PAUSE) {
				Log.i(TAG, "I got a pause message");
				jukebox.pause();
			} else if (command == MSG_PLAY) {
				Log.i(TAG, "I got a play message");
				jukebox.play();
			} else if (command == MSG_NEXT) {
				Log.i(TAG, "I got a next message");
				jukebox.next();
			} else if (command == MSG_PREVIOUS) {
				Log.i(TAG, "I got a previous message");
				jukebox.previous();
			} else if (command == MSG_REWIND) {
				Log.i(TAG, "I got a rewind message");
				jukebox.rewind(20);
			} else if (command == MSG_STOP_SERVICE) {
				Log.i(TAG, "I got a stop message");
				headphoneReceiver.ignoreEvents();
				timer.cancel();
				stopForeground(true);
				stopSelf();
			} else if (command == MSG_PAUSE_IN_ONE_SEC) {
				pauseTime = System.currentTimeMillis() + 1000;
			} else if (command == MSG_CANCEL_PAUSE_IN_ONE_SEC) {
				pauseTime = Long.MAX_VALUE;
			}
			return START_STICKY;
		}

		Message msg = mServiceHandler.obtainMessage();
		msg.arg1 = startId;
		mServiceHandler.sendMessage(msg);
		// If we get killed, after returning from here, restart
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	// Receives messages from activities which want to control the jams
	private static class IncomingHandler extends Handler {
		private final MusicPlaybackService _service;
		private Jukebox jukebox;

		private IncomingHandler(MusicPlaybackService service) {
			_service = service;
		}

		void addJukebox(Jukebox jukebox) {
			this.jukebox = jukebox;
		}
		@Override
		public void handleMessage(Message msg) {
			Log.i(TAG, "Music Playback service got a message!");
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				Log.i(TAG, "Got MSG_REGISTER_CLIENT");
				synchronized (_service) {
					_service.mClients.add(msg.replyTo);
				}
				break;
			case MSG_UNREGISTER_CLIENT:
				Log.i(TAG, "Got MSG_UNREGISTER_CLIENT");
				synchronized (_service) {
					_service.mClients.remove(msg.replyTo);
				}
				break;
			case MSG_PLAYPAUSE:
				// if we got a playpause message, assume that the user can hear
				// what's happening and wants to switch it.
				Log.i(TAG, "Got a playpause message!");
				// Assume that we're not changing songs
				jukebox.playOrPause();
				break;
			case MSG_NEXT:
				Log.i(TAG, "Got a next message!");
				jukebox.next();
				break;
			case MSG_PREVIOUS:
				Log.i(TAG, "Got a previous message!");
				jukebox.previous();
				break;
			case MSG_REWIND:
				Log.i(TAG, "Got a jump back message!");
				jukebox.rewind(20);
				break;
			case MSG_TOGGLE_SHUFFLE:
				Log.i(TAG, "Got a toggle shuffle message!");
				_service.toggleShuffle();
				break;
			case MSG_SET_PLAYLIST:
				Log.i(TAG, "Got a set playlist message!");
				jukebox.setPlaylist(msg.getData().getStringArray(
						SongList.SONG_ABS_FILE_NAME_LIST));
				jukebox.setPlaylistIndex(msg.getData().getInt(
						SongList.SONG_ABS_FILE_NAME_LIST_POSITION));
				_service.artist = msg.getData().getString(ArtistList.ARTIST_NAME);
				_service.artistAbsPath = msg.getData().getString(ArtistList.ARTIST_ABS_PATH_NAME);
				_service.album = msg.getData().getString(AlbumList.ALBUM_NAME);
				int songPosition = msg.getData().getInt(TRACK_POSITION, 0);
				jukebox.play(songPosition);
				break;
			case MSG_REQUEST_STATE:
				Log.i(TAG, "Got a state request message!");
				break;
			case MSG_SEEK_TO:
				Log.i(TAG, "Got a seek request message!");
				int progress = msg.getData().getInt(TRACK_POSITION);
				jukebox.seekTo(progress);
				break;
			default:
				super.handleMessage(msg);
			}
		}
	}

	private void onTimerTick() {
		long now = System.currentTimeMillis();
		if (pauseTime < now) {
			jukebox.pause();
		}
		updateResumePosition();
		sendUpdateToClients();
	}

	private void updateResumePosition(){
		long now = System.currentTimeMillis();
		if(now - 10000 > lastResumeUpdateTime){
			if(jukebox != null && jukebox.isPlaying()){
				int pos = jukebox.getSongPlaybackPosition();
				SharedPreferences prefs = getSharedPreferences("PrettyGoodMusicPlayer", MODE_PRIVATE);
				File songFile = jukebox.getCurrentSongFile();
				Log.i(TAG, "Preferences update success: "
						+ prefs.edit().putString(
						songFile.getParentFile().getAbsolutePath(),
						songFile.getName() + "~" + pos)
						  .commit());
			}
			lastResumeUpdateTime = now;
		}
	}

	private synchronized void sendUpdateToClients() {
		List<Messenger> toRemove = new ArrayList<>();
		for (Messenger client : mClients) {
			Message msg = Message.obtain(null, MSG_SERVICE_STATUS);
			Bundle b = new Bundle();
			if (jukebox != null && jukebox.getCurrentSongFile() != null) {
				File songFile = jukebox.getCurrentSongFile();
				b.putString(PRETTY_SONG_NAME,
						Utils.getPrettySongName(songFile));
				b.putString(PRETTY_ALBUM_NAME, songFile.getParentFile()
						.getName());
				b.putString(PRETTY_ARTIST_NAME, songFile.getParentFile()
						.getParentFile().getName());
			} else {
				// songFile can be null while we're shutting down.
				b.putString(PRETTY_SONG_NAME, " ");
				b.putString(PRETTY_ALBUM_NAME, " ");
				b.putString(PRETTY_ARTIST_NAME, " ");
			}

			b.putBoolean(IS_SHUFFLING, this._shuffle);
			b.putInt(PLAYBACK_STATE, jukebox.getPlayBackState().ordinal());

			b.putInt(TRACK_DURATION, jukebox.getSongDuration());
			b.putInt(TRACK_POSITION, jukebox.getSongPlaybackPosition());
			msg.setData(b);
			try {
				client.send(msg);
			} catch (RemoteException e) {
				e.printStackTrace();
				toRemove.add(client);
			}
		}

		for (Messenger remove : toRemove) {
			mClients.remove(remove);
		}
	}

	public static boolean isRunning() {
		return isRunning;
	}

	@Override
	public synchronized void onDestroy() {
		super.onDestroy();
		unregisterReceiver(headphoneReceiver);
		am.abandonAudioFocus(audioFocusListener);
		mAudioManager.unregisterMediaButtonEventReceiver(cn);
		getApplicationContext().unregisterReceiver(receiver);
		jukebox.onDestroy();
		Log.i("MyService", "Service Stopped.");
		isRunning = false;
	}

	public void toggleShuffle() {
		this._shuffle = !this._shuffle ;
	}

	@Override
	public void onPlaybackStateChange() {
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		if (jukebox.getPlayBackState() != Jukebox.PlaybackState.STOPPED && jukebox.getCurrentSongFile() != null) {
			mNotificationManager.notify(uid, createNotification(jukebox.getCurrentSongFile(), jukebox.getPlayBackState()));
		}
		else {
			mNotificationManager.cancel(uid);
		}
	}

	private Notification createNotification(File song, Jukebox.PlaybackState state) {
		boolean audiobookMode = sharedPref.getBoolean("pref_audiobook_mode", false);

		// https://stackoverflow.com/questions/5528288/how-do-i-update-the-notification-text-for-a-foreground-service-in-android
		Intent resultIntent = new Intent(this, NowPlaying.class);
		// Use the FLAG_ACTIVITY_CLEAR_TOP to prevent launching a second
		// NowPlaying if one already exists.
		resultIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		resultIntent.putExtra("From_Notification", true);
		resultIntent.putExtra(AlbumList.ALBUM_NAME, album);
		resultIntent.putExtra(ArtistList.ARTIST_NAME, artist);
		resultIntent.putExtra(ArtistList.ARTIST_ABS_PATH_NAME, artistAbsPath);

		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
				resultIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		Builder builder = new NotificationCompat.Builder(
				this.getApplicationContext());
		int icon = R.drawable.ic_pgmp_launcher;
		String contentText = getResources().getString(R.string.ticker_text);
		if (jukebox != null && jukebox.getCurrentSongFile() != null) {
			File songFile = jukebox.getCurrentSongFile();
			SharedPreferences prefs = getSharedPreferences(
					"PrettyGoodMusicPlayer", MODE_PRIVATE);
			prefs.edit().apply();
			File bestGuessMusicDir = Utils.getBestGuessMusicDirectory();
			String musicRoot = prefs.getString("ARTIST_DIRECTORY",
					bestGuessMusicDir.getAbsolutePath());
			contentText = Utils.getArtistName(songFile, musicRoot) + ": "
					+ Utils.getPrettySongName(songFile);
			if (isRunning()) {
				if (jukebox.isPlaying()) {
					icon = R.drawable.ic_pgmp_launcher;
				}
			}
		}

		Intent previousIntent = new Intent("Previous", null, this, MusicPlaybackService.class);
		previousIntent.putExtra("Message", MSG_PREVIOUS);
		PendingIntent previousPendingIntent = PendingIntent.getService(this, 0, previousIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		Intent jumpBackIntent = new Intent("JumpBack", null, this, MusicPlaybackService.class);
		jumpBackIntent.putExtra("Message", MSG_REWIND);
		PendingIntent jumpBackPendingIntent = PendingIntent.getService(this, 0, jumpBackIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		Intent nextIntent = new Intent("Next", null, this, MusicPlaybackService.class);
		nextIntent.putExtra("Message", MSG_NEXT);
		PendingIntent nextPendingIntent = PendingIntent.getService(this, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		PendingIntent playPausePendingIntent;
		Intent playPauseIntent = new Intent("PlayPause", null, this, MusicPlaybackService.class);
		playPauseIntent.putExtra("Message", MSG_PLAYPAUSE);
		playPausePendingIntent = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT);
		int playPauseIcon;
		if(isRunning() && jukebox.isPlaying()){
			playPauseIcon = R.drawable.ic_action_pause;
		} else {
			playPauseIcon = R.drawable.ic_action_play;
		}

		Notification notification;
		if(audiobookMode){
			notification = builder
					.setContentText(contentText)
					.setSmallIcon(icon)
					.setWhen(System.currentTimeMillis())
					.setContentIntent(pendingIntent)
					.setContentTitle(
							getResources().getString(R.string.notification_title))
							.addAction(R.drawable.ic_action_rewind20, "", jumpBackPendingIntent)
							.addAction(playPauseIcon, "", playPausePendingIntent)
							.addAction(R.drawable.ic_action_next, "", nextPendingIntent)
							.build();
		} else {
			notification = builder
					.setContentText(contentText)
					.setSmallIcon(icon)
					.setWhen(System.currentTimeMillis())
					.setContentIntent(pendingIntent)
					.setContentTitle(
							getResources().getString(R.string.notification_title))
							.addAction(R.drawable.ic_action_previous, "", previousPendingIntent)
							.addAction(playPauseIcon, "", playPausePendingIntent)
							.addAction(R.drawable.ic_action_next, "", nextPendingIntent)
							.build();
		}

		return notification;
	}
}
