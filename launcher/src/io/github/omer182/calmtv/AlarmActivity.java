package io.github.omer182.calmtv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/** The ringing screen: big clock and greeting, then a tune or a random morning song on YouTube. */
public class AlarmActivity extends Activity {
    private static final long RAMP_MS = 30_000;
    private static final long AUTO_STOP_MS = 15 * 60_000;
    private static final long SONG_GREETING_MS = 5_000;

    private final Handler handler = new Handler();
    private MediaPlayer player;
    private TextView status;
    private long rampStart;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        setVolume();

        AlarmStore alarm = AlarmStore.load(this);
        if (alarm.sound == AlarmStore.SOUND_RANDOM_SONG) {
            if (!playSpotify()) playRandomSong();
        } else {
            playTune(AlarmStore.TUNES[alarm.sound]);
        }
        handler.postDelayed(this::stop, AUTO_STOP_MS);
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setImageBitmap(randomWallpaper());
        photo.setAlpha(0.55f);
        root.addView(photo, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(140);
        clock.setTypeface(Ui.inter(this, 200));
        clock.setGravity(Gravity.CENTER);
        col.addView(clock);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        TextView hello = Ui.text(this, 40, 300);
        hello.setText(Config.get(this).greet(Ui.partOfDay(hour), ", "));
        hello.setGravity(Gravity.CENTER);
        col.addView(hello);

        Weather w = Weather.load(getSharedPreferences("dock", MODE_PRIVATE));
        String today = w != null ? w.todayPhrase() : null;
        if (today != null) {
            TextView weather = Ui.text(this, 22, 400);
            weather.setAlpha(0.85f);
            weather.setPadding(0, Ui.dp(this, 10), 0, 0);
            weather.setText(today);
            weather.setGravity(Gravity.CENTER);
        col.addView(weather);
        }

        status = Ui.text(this, 18, 400);
        status.setAlpha(0.7f);
        status.setPadding(0, Ui.dp(this, 48), 0, 0);
        status.setText("Press OK to stop");
        status.setGravity(Gravity.CENTER);
        col.addView(status);

        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private Bitmap randomWallpaper() {
        List<Integer> ids = new ArrayList<>();
        for (Field f : R.drawable.class.getFields()) {
            if (!f.getName().startsWith("wp_")) continue;
            try {
                ids.add(f.getInt(null));
            } catch (IllegalAccessException ignored) {
            }
        }
        if (ids.isEmpty()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeResource(getResources(), ids.get(new Random().nextInt(ids.size())), opts);
    }

    private void setVolume() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int volume = Math.min(Config.get(this).alarmVolume, max);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        Log.i(AlarmReceiver.TAG, "volume set to " + volume + " of " + max);
    }

    private void playTune(int rawId) {
        player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(getResources().openRawResourceFd(rawId));
            player.setLooping(true);
            player.prepare();
        } catch (Exception e) {
            Log.w(AlarmReceiver.TAG, "tune failed", e);
            return;
        }
        // Start soft and grow to full over 30 s.
        player.setVolume(0.25f, 0.25f);
        player.start();
        rampStart = System.currentTimeMillis();
        handler.post(ramp);
    }

    private final Runnable ramp = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            float f = Math.min(1f, 0.25f + 0.75f * (System.currentTimeMillis() - rampStart) / RAMP_MS);
            player.setVolume(f, f);
            if (f < 1f) handler.postDelayed(this, 500);
        }
    };

    private static final int MAX_SKIPS = 24;

    /**
     * Opens the playlist in Spotify (signed in, no profile picker) and then skips ahead a random
     * number of tracks with media keys, so each morning starts on a different song.
     */
    private boolean playSpotify() {
        String playlist = Config.get(this).spotifyPlaylist;
        if (playlist.isEmpty()) return false;
        // The ":play" suffix makes Spotify start playing on open.
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:playlist:" + playlist + ":play"))
                .setPackage("com.spotify.tv.android")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (open.resolveActivity(getPackageManager()) == null) return false;
        status.setText("Playing your morning playlist…");
        final int skips = new Random().nextInt(MAX_SKIPS + 1);
        final AudioManager am = (AudioManager) getApplicationContext().getSystemService(AUDIO_SERVICE);
        // Keep the greeting up for a moment, then hand over to Spotify.
        handler.postDelayed(() -> {
            if (finished) return;
            try {
                startActivity(open);
            } catch (ActivityNotFoundException e) {
                playRandomSong();
                return;
            }
            Log.i(AlarmReceiver.TAG, "spotify playlist, skipping " + skips);
            // Runs on the main looper, so it keeps going after this screen closes.
            Handler main = new Handler(getMainLooper());
            for (int i = 0; i < skips; i++) {
                // Give Spotify ~12 s to load and start (the network may just be up after wake).
                main.postDelayed(() -> {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT));
                }, 12_000 + i * 700L);
            }
            finished = true;
            finish();
        }, SONG_GREETING_MS);
        return true;
    }

    private void playRandomSong() {
        final String song = MorningSongs.pick();
        status.setText("Finding a morning song…");
        final long shownAt = System.currentTimeMillis();
        new Thread(() -> {
            // Right after Wake-on-LAN the network may still be coming up, so retry for a bit.
            String id = null;
            for (int i = 0; i < 6 && id == null && !finished; i++) {
                id = MorningSongs.findVideoId(song);
                if (id == null) sleepQuietly(5_000);
            }
            final String videoId = id;
            long wait = Math.max(0, SONG_GREETING_MS - (System.currentTimeMillis() - shownAt));
            handler.postDelayed(() -> {
                if (finished) return;
                if (videoId == null) {
                    Log.w(AlarmReceiver.TAG, "no video for " + song + ", playing a tune instead");
                    status.setText("Press OK to stop");
                    playTune(AlarmStore.TUNES[0]);
                    return;
                }
                Log.i(AlarmReceiver.TAG, "playing " + song + " (" + videoId + ")");
                openYouTube(videoId);
            }, wait);
        }).start();
    }

    private void openYouTube(String videoId) {
        Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + videoId))
                .setPackage("com.google.android.youtube.tv")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(yt);
            finished = true;
            finish();
        } catch (ActivityNotFoundException e) {
            playTune(AlarmStore.TUNES[0]);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any button stops the alarm.
        if (event.getAction() == KeyEvent.ACTION_UP) stop();
        return true;
    }

    private void stop() {
        finished = true;
        finish();
    }

    @Override
    protected void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
