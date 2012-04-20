/*
 * Copyright 2012 Kevin Sawicki <kevinsawicki@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.play.app;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static com.github.play.app.StatusService.UPDATE;
import android.app.AlertDialog.Builder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ListView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.github.play.R.drawable;
import com.github.play.R.id;
import com.github.play.R.layout;
import com.github.play.R.menu;
import com.github.play.R.string;
import com.github.play.core.DequeueSongTask;
import com.github.play.core.FetchSettingsTask;
import com.github.play.core.FetchStatusTask;
import com.github.play.core.PlayPreferences;
import com.github.play.core.PlayService;
import com.github.play.core.Song;
import com.github.play.core.SongCallback;
import com.github.play.core.StarSongTask;
import com.github.play.core.StatusUpdate;
import com.github.play.core.StreamingInfo;
import com.github.play.core.UnstarSongTask;
import com.github.play.widget.NowPlayingViewWrapper;
import com.github.play.widget.PlayListAdapter;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Activity to view what is playing and listen to music
 */
public class PlayActivity extends SherlockActivity implements SongCallback {

	private static final String TAG = "PlayActivity";

	private static final String STREAMING_INFO = "streamingInfo";

	private static final int REQUEST_SETTINGS = 1;

	private final AtomicReference<PlayService> playService = new AtomicReference<PlayService>();

	private NowPlayingViewWrapper nowPlayingItemView;

	private PlayListAdapter playListAdapter;

	private boolean streaming = false;

	private MenuItem playItem;

	private PlayPreferences settings;

	private StreamingInfo streamingInfo;

	private BroadcastReceiver receiver = new BroadcastReceiver() {

		public void onReceive(Context context, Intent intent) {
			StatusUpdate update = (StatusUpdate) intent
					.getSerializableExtra("update");
			onUpdate(update.playing, update.queued);
		}
	};

	private OnClickListener starListener = new OnClickListener() {

		public void onClick(View v) {
			Object tag = v.getTag();
			if (!(tag instanceof Song))
				return;

			Song song = (Song) tag;
			if (song.starred)
				unstarSong(song);
			else
				starSong(song);
		}
	};

	private OnItemLongClickListener dequeueListener = new OnItemLongClickListener() {

		public boolean onItemLongClick(AdapterView<?> parent, View view,
				int position, long id) {
			Song song = (Song) parent.getItemAtPosition(position);
			dequeueSong(song);
			return true;
		}
	};

	@Override
	protected void onDestroy() {
		super.onDestroy();

		unregisterReceiver(receiver);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		outState.putSerializable(STREAMING_INFO, streamingInfo);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(layout.main);

		ListView list = (ListView) findViewById(android.R.id.list);
		list.setOnItemLongClickListener(dequeueListener);

		View nowPlayingView = findViewById(id.now_playing);
		nowPlayingItemView = new NowPlayingViewWrapper(nowPlayingView,
				playService, starListener);

		playListAdapter = new PlayListAdapter(layout.queued,
				getLayoutInflater(), playService, starListener);
		list.setAdapter(playListAdapter);

		if (savedInstanceState != null)
			streamingInfo = (StreamingInfo) savedInstanceState
					.getSerializable(STREAMING_INFO);

		settings = new PlayPreferences(this);

		if (settings.getUrl() != null && settings.getToken() != null) {
			playService.set(new PlayService(settings.getUrl(), settings
					.getToken()));
			load();
		} else
			startActivityForResult(new Intent(this, SettingsActivity.class),
					REQUEST_SETTINGS);

		registerReceiver(receiver, new IntentFilter(UPDATE));
	}

	private void startStream() {
		Log.d(TAG, "Starting stream");

		if (playItem != null) {
			playItem.setIcon(drawable.action_pause);
			playItem.setTitle(string.pause);
		}

		Context context = getApplicationContext();
		MusicStreamService.start(context, streamingInfo.streamUrl);
		StatusService.start(context, streamingInfo.pusherKey);

		streaming = true;
	}

	private void load() {
		if (streamingInfo == null)
			new FetchSettingsTask(playService) {

				protected void onPostExecute(PlaySettings result) {
					if (result.streamingInfo != null) {
						streamingInfo = result.streamingInfo;
						load();
					} else
						onError(result.exception);
				}

			}.execute();
		else {
			refreshSongs();
			startStream();
		}
	}

	private void stopStream() {
		Log.d(TAG, "Stopping stream");

		if (playItem != null) {
			playItem.setIcon(drawable.action_play);
			playItem.setTitle(string.play);
		}

		Context context = getApplicationContext();
		MusicStreamService.stop(context);
		StatusService.stop(context);

		streaming = false;
	}

	public void onUpdate(final Song playing, final Song[] queued) {
		runOnUiThread(new Runnable() {

			public void run() {
				updateSongs(playing, queued);
			}
		});
	}

	private void updateSongs(Song playing, Song[] queued) {
		nowPlayingItemView.update(playing);
		playListAdapter.setItems(queued);
	}

	private void refreshSongs() {
		new FetchStatusTask(playService, this).execute();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case id.m_pause:
			if (settings.getUrl() != null && settings.getToken() != null)
				if (streaming)
					stopStream();
				else
					startStream();
			return true;
		case id.m_refresh:
			refreshSongs();
			return true;
		case id.m_settings:
			startActivityForResult(new Intent(this, SettingsActivity.class),
					REQUEST_SETTINGS);
		default:
			return super.onOptionsItemSelected(item);
		}

	}

	@Override
	public boolean onCreateOptionsMenu(Menu optionsMenu) {
		getSupportMenuInflater().inflate(menu.main, optionsMenu);
		playItem = optionsMenu.findItem(id.m_pause);

		if (streaming) {
			playItem.setIcon(drawable.action_pause);
			playItem.setTitle(string.pause);
		} else {
			playItem.setIcon(drawable.action_play);
			playItem.setTitle(string.play);
		}

		return true;
	}

	public void onError(IOException e) {
		Log.d(TAG, "Play server exception", e);

		Toast.makeText(
				getApplicationContext(),
				MessageFormat.format(
						getString(string.error_contacting_play_server),
						e.getMessage()), LENGTH_LONG).show();
	}

	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
			playService.set(new PlayService(settings.getUrl(), settings
					.getToken()));
			streamingInfo = null;
			load();
			return;
		} else
			super.onActivityResult(requestCode, resultCode, data);
	}

	private void starSong(final Song song) {
		Toast.makeText(
				getApplicationContext(),
				MessageFormat
						.format(getString(string.starring_song), song.name),
				LENGTH_SHORT).show();
		new StarSongTask(playService) {

			@Override
			protected void onPostExecute(IOException result) {
				super.onPostExecute(result);

				if (result != null)
					Toast.makeText(
							getApplicationContext(),
							MessageFormat.format(
									getString(string.starring_failed),
									song.name), LENGTH_LONG).show();
				else
					refreshSongs();
			}
		}.execute(song);
	}

	private void unstarSong(final Song song) {
		Toast.makeText(
				getApplicationContext(),
				MessageFormat.format(getString(string.unstarring_song),
						song.name), LENGTH_SHORT).show();
		new UnstarSongTask(playService) {

			@Override
			protected void onPostExecute(IOException result) {
				super.onPostExecute(result);

				if (result != null)
					Toast.makeText(
							getApplicationContext(),
							MessageFormat.format(
									getString(string.unstarring_failed),
									song.name), LENGTH_SHORT).show();
				else
					refreshSongs();
			}
		}.execute(song);
	}

	private void dequeueSong(final Song song) {
		final Builder builder = new Builder(this);
		builder.setCancelable(true);
		builder.setTitle(string.title_confirm_remove);
		builder.setMessage(MessageFormat.format(
				getString(string.message_confirm_remove), song.name));
		builder.setNegativeButton(android.R.string.no, null);
		builder.setPositiveButton(android.R.string.yes,
				new DialogInterface.OnClickListener() {

					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						Toast.makeText(
								getApplicationContext(),
								MessageFormat.format(
										getString(string.removing_song),
										song.name), LENGTH_SHORT).show();
						new DequeueSongTask(playService) {

							@Override
							protected void onPostExecute(IOException result) {
								super.onPostExecute(result);

								if (result != null)
									Toast.makeText(
											getApplicationContext(),
											MessageFormat
													.format(getString(string.removing_song_failed),
															song.name),
											LENGTH_SHORT).show();
								else
									refreshSongs();
							}
						}.execute(song);
					}
				});
		builder.show();
	}
}