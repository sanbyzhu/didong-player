package com.dongting.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class TextReaderService extends Service implements TextToSpeech.OnInitListener {
    static final String ACTION_START = "com.dongting.player.TEXT_START";
    static final String ACTION_TOGGLE = "com.dongting.player.TEXT_TOGGLE";
    static final String ACTION_PREVIOUS = "com.dongting.player.TEXT_PREVIOUS";
    static final String ACTION_NEXT = "com.dongting.player.TEXT_NEXT";
    static final String ACTION_RESTART = "com.dongting.player.TEXT_RESTART";
    static final String ACTION_STOP = "com.dongting.player.TEXT_STOP";
    static final String ACTION_SEEK_CHUNK = "com.dongting.player.TEXT_SEEK_CHUNK";
    static final String ACTION_BACKGROUND_UPDATE = "com.dongting.player.TEXT_BACKGROUND_UPDATE";
    static final String ACTION_BACKGROUND_STOP = "com.dongting.player.TEXT_BACKGROUND_STOP";

    static final String EXTRA_TEXT_URI = "text_uri";
    static final String EXTRA_CHUNK_INDEX = "chunk_index";
    static final String EXTRA_TEXT_CONTENT = "text_content";

    private static final String PREFS = "dongting_player";
    private static final String CHANNEL_ID = "dongting_text_reader";
    private static final int NOTIFICATION_ID = 20260503;
    private static final int MAX_TTS_ERRORS = 5;
    private static final int EXTERNAL_TTS_MAX_CHARS = 180;
    private static boolean serviceRunning = false;
    private static boolean servicePaused = true;
    private static boolean rangeTrackingSupported = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> chunks = new ArrayList<>();
    private SharedPreferences prefs;
    private TextToSpeech tts;
    private ExoPlayer bgPlayer;
    private ExoPlayer externalTtsPlayer;
    private String textUri = "";
    private String title = "TXT 朗读";
    private int currentChunk = 0;
    private int currentSpeakOffset = 0;
    private int consecutiveErrors = 0;
    private boolean ready = false;
    private boolean paused = false;

    @Override
    public void onCreate() {
        super.onCreate();
        serviceRunning = true;
        servicePaused = true;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        bgPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, false).build();
        externalTtsPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build();
        externalTtsPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    prefs.edit()
                            .putString("lastExternalTtsState", "READY")
                            .putString("ttsVisualMode:" + textUri, "chunk")
                            .putInt("ttsRangeStart:" + textUri, 0)
                            .putInt("ttsRangeEnd:" + textUri, 0)
                            .apply();
                }
                if (playbackState == Player.STATE_ENDED) mainHandler.post(() -> {
                    prefs.edit().putString("lastExternalTtsState", "ENDED").apply();
                    if (paused) return;
                    consecutiveErrors = 0;
                    currentChunk++;
                    if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
                    speakCurrent();
                });
            }

            @Override public void onPlayerError(PlaybackException error) {
                mainHandler.post(() -> {
                    if (!paused && !chunks.isEmpty()) {
                        prefs.edit()
                                .putString("lastExternalTtsState", "ERROR")
                                .putString("lastExternalTtsError", error.getErrorCodeName())
                                .apply();
                        fallbackFromExternalTts();
                    }
                });
            }
        });
        applyBackgroundRepeatMode();
        createTtsFromSystemDefault();
    }

    private void createTtsFromSystemDefault() {
        String engine = currentSystemTtsEngine();
        if (engine.isEmpty()) {
            tts = new TextToSpeech(this, this);
        } else {
            tts = new TextToSpeech(this, this, engine);
        }
    }

    private String currentSystemTtsEngine() {
        try {
            String engine = android.provider.Settings.Secure.getString(getContentResolver(), "tts_default_synth");
            return engine == null ? "" : engine.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            String uri = intent.getStringExtra(EXTRA_TEXT_URI);
            String content = intent.getStringExtra(EXTRA_TEXT_CONTENT);
            if (content != null && !content.trim().isEmpty()) {
                textUri = "inline:" + content.hashCode();
                title = "TXT 朗读";
                currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
                paused = false;
                loadTextContent(content);
                if (ready || useExternalTts()) speakCurrent();
                updateNotification(true);
            } else if (uri != null && !uri.isEmpty()) {
                textUri = uri;
                title = readDisplayName(Uri.parse(uri));
                currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
                paused = false;
                loadText(Uri.parse(uri));
                if (ready || useExternalTts()) speakCurrent();
                updateNotification(true);
            }
        } else if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_PREVIOUS.equals(action)) {
            moveChunk(-1);
        } else if (ACTION_NEXT.equals(action)) {
            moveChunk(1);
        } else if (ACTION_RESTART.equals(action)) {
            currentChunk = 0;
            if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, 0).apply();
            speakCurrent();
        } else if (ACTION_SEEK_CHUNK.equals(action)) {
            seekChunk(intent.getIntExtra(EXTRA_CHUNK_INDEX, currentChunk));
        } else if (ACTION_STOP.equals(action)) {
            stopReading();
        } else if (ACTION_BACKGROUND_UPDATE.equals(action)) {
            updateBackgroundMusicFromPrefs();
        } else if (ACTION_BACKGROUND_STOP.equals(action)) {
            if (bgPlayer != null) {
                bgPlayer.pause();
                bgPlayer.seekTo(0);
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (!ready) {
            updateNotification(false);
            return;
        }
        tts.setLanguage(Locale.CHINA);
        applyTtsSettings();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
            }

            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> {
                    if (paused) return;
                    consecutiveErrors = 0;
                    currentChunk++;
                    if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
                    speakCurrent();
                });
            }

            @Override public void onError(String utteranceId) {
                mainHandler.post(() -> continueAfterTtsError());
            }

            @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                if (textUri.isEmpty()) return;
                rangeTrackingSupported = true;
                int safeStart = Math.max(0, currentSpeakOffset + start);
                int safeEnd = Math.max(safeStart, currentSpeakOffset + end);
                prefs.edit()
                        .putInt("ttsRangeStart:" + textUri, safeStart)
                        .putInt("ttsRangeEnd:" + textUri, safeEnd)
                        .apply();
            }
        });
        if (!useExternalTts() && !textUri.isEmpty() && !chunks.isEmpty()) speakCurrent();
    }

    @Override
    public void onDestroy() {
        serviceRunning = false;
        servicePaused = true;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (externalTtsPlayer != null) externalTtsPlayer.release();
        if (bgPlayer != null) bgPlayer.release();
        super.onDestroy();
    }

    static boolean isRunning() {
        return serviceRunning;
    }

    static boolean isPaused() {
        return servicePaused;
    }

    static boolean isRangeTrackingSupported() {
        return rangeTrackingSupported;
    }

    private void loadText(Uri uri) {
        chunks.clear();
        String text = readText(uri);
        chunks.addAll(splitTextForTts(text));
        currentChunk = Math.min(currentChunk, Math.max(0, chunks.size() - 1));
    }

    private void loadTextContent(String text) {
        chunks.clear();
        chunks.addAll(splitTextForTts(text == null ? "" : text));
        currentChunk = Math.min(currentChunk, Math.max(0, chunks.size() - 1));
    }

    private void speakCurrent() {
        if (chunks.isEmpty()) return;
        if (!useExternalTts() && (!ready || tts == null)) return;
        if (currentChunk >= chunks.size()) {
            if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, 0).apply();
            if (playNextTextBook()) return;
            stopReading();
            return;
        }
        boolean resumeFromPaused = paused;
        String chunk = currentSpeakText();
        int rangeStart = !textUri.isEmpty() ? prefs.getInt("ttsRangeStart:" + textUri, 0) : 0;
        currentSpeakOffset = resumeFromPaused ? Math.max(0, Math.min(rangeStart, Math.max(0, chunk.length() - 1))) : 0;
        String speakText = chunk.substring(currentSpeakOffset);
        if (!textUri.isEmpty()) {
            prefs.edit()
                    .putInt("ttsChunk:" + textUri, currentChunk)
                    .putInt("ttsRangeStart:" + textUri, currentSpeakOffset)
                    .putInt("ttsRangeEnd:" + textUri, Math.min(chunk.length(), currentSpeakOffset + 1))
                    .putString("ttsVisualMode:" + textUri, useExternalTts() ? "chunk" : "range")
                    .apply();
        }
        paused = false;
        servicePaused = false;
        applyTtsSettings();
        startBackgroundMusic();
        updateNotification(true);
        if (useExternalTts()) {
            speakCurrentWithExternalTts(speakText);
            return;
        }
        speakCurrentWithSystemTts(speakText);
    }

    private String currentSpeakText() {
        if (chunks.isEmpty()) return "";
        return chunks.get(Math.max(0, Math.min(currentChunk, chunks.size() - 1)));
    }

    private boolean playNextTextBook() {
        String next = nextTextBookUri();
        if (next.isEmpty()) return false;
        textUri = next;
        title = readDisplayName(Uri.parse(next));
        currentChunk = Math.max(0, prefs.getInt("ttsChunk:" + textUri, 0));
        loadText(Uri.parse(next));
        if (chunks.isEmpty()) return playNextTextBook();
        prefs.edit().putString("lastTextUri", textUri).apply();
        speakCurrent();
        return true;
    }

    private String nextTextBookUri() {
        if (textUri == null || textUri.isEmpty() || textUri.startsWith("inline:")) return "";
        List<String> books = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("textBooks", "[]"));
            for (int i = 0; i < array.length(); i++) {
                String uri = array.getJSONObject(i).optString("uri", "");
                if (!uri.isEmpty()) books.add(uri);
            }
        } catch (JSONException ignored) {
        }
        int index = books.indexOf(textUri);
        if (index < 0 || index + 1 >= books.size()) return "";
        return books.get(index + 1);
    }

    private void speakCurrentWithSystemTts(String speakText) {
        if (!ready || tts == null || speakText == null || speakText.isEmpty()) return;
        if (externalTtsPlayer != null) externalTtsPlayer.stop();
        int result = tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "dongting_reader_" + currentChunk);
        if (result == TextToSpeech.ERROR) continueAfterTtsError();
    }

    private void speakCurrentWithExternalTts(String speakText) {
        if (externalTtsPlayer == null || speakText == null || speakText.trim().isEmpty()) {
            speakCurrentWithSystemTts(speakText);
            return;
        }
        if (tts != null) tts.stop();
        try {
            Uri uri = buildExternalTtsUri(speakText);
            prefs.edit()
                    .putString("lastExternalTtsState", "REQUEST")
                    .putString("lastExternalTtsError", "")
                    .putString("lastExternalTtsInfo", "chunk=" + currentChunk + ", chars=" + speakText.length()
                            + ", voice=" + prefs.getString("externalTtsVoice", ""))
                    .apply();
            externalTtsPlayer.stop();
            externalTtsPlayer.clearMediaItems();
            externalTtsPlayer.setMediaItem(MediaItem.fromUri(uri));
            externalTtsPlayer.prepare();
            externalTtsPlayer.play();
        } catch (Exception ex) {
            prefs.edit().putString("lastExternalTtsError", ex.getClass().getSimpleName()).apply();
            fallbackFromExternalTts();
        }
    }

    private void fallbackFromExternalTts() {
        if (paused || chunks.isEmpty()) return;
        if (ready && tts != null) {
            speakCurrentWithSystemTts(currentSpeakText());
            return;
        }
        continueAfterTtsError();
    }

    private Uri buildExternalTtsUri(String text) throws Exception {
        String base = prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward");
        if (base == null || base.trim().isEmpty()) base = "http://127.0.0.1:8774/forward";
        String separator = base.contains("?") ? "&" : "?";
        int speed = Math.max(0, Math.min(100, Math.round(prefs.getInt("ttsRate", 75) * 100f / 150f)));
        int pitch = Math.max(0, Math.min(100, prefs.getInt("ttsPitch", 50)));
        int volume = Math.max(0, Math.min(100, prefs.getInt("externalTtsVolume", 100)));
        String voice = prefs.getString("externalTtsVoice", "");
        StringBuilder url = new StringBuilder(base.trim())
                .append(separator)
                .append("text=").append(URLEncoder.encode(text, "UTF-8"))
                .append("&speed=").append(speed)
                .append("&volume=").append(volume)
                .append("&pitch=").append(pitch);
        if (voice != null && !voice.trim().isEmpty()) {
            url.append("&voice=").append(URLEncoder.encode(voice.trim(), "UTF-8"));
        }
        return Uri.parse(url.toString());
    }

    private boolean useExternalTts() {
        return "multitts".equals(prefs.getString("ttsProvider", "system"));
    }

    private void continueAfterTtsError() {
        if (paused || chunks.isEmpty()) return;
        consecutiveErrors++;
        currentChunk++;
        if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
        if (consecutiveErrors >= MAX_TTS_ERRORS) {
            pauseReading();
            return;
        }
        mainHandler.postDelayed(this::speakCurrent, 300);
    }

    private void toggle() {
        if (paused) {
            if (useExternalTts() && externalTtsPlayer != null && externalTtsPlayer.getMediaItemCount() > 0) {
                paused = false;
                servicePaused = false;
                startBackgroundMusic();
                updateNotification(true);
                externalTtsPlayer.play();
            } else {
                speakCurrent();
            }
        } else {
            pauseReading();
        }
    }

    private void moveChunk(int delta) {
        if (chunks.isEmpty()) return;
        seekChunk(currentChunk + delta);
    }

    private void seekChunk(int index) {
        if (chunks.isEmpty()) return;
        currentChunk = Math.max(0, Math.min(chunks.size() - 1, index));
        if (!textUri.isEmpty()) {
            prefs.edit()
                    .putInt("ttsChunk:" + textUri, currentChunk)
                    .putInt("ttsRangeStart:" + textUri, 0)
                    .putInt("ttsRangeEnd:" + textUri, 0)
                    .apply();
        }
        paused = false;
        speakCurrent();
    }

    private void pauseReading() {
        paused = true;
        servicePaused = true;
        if (tts != null) tts.stop();
        if (externalTtsPlayer != null) externalTtsPlayer.pause();
        if (bgPlayer != null) bgPlayer.pause();
        if (!textUri.isEmpty()) prefs.edit().putInt("ttsChunk:" + textUri, currentChunk).apply();
        updateNotification(false);
    }

    private void stopReading() {
        paused = true;
        servicePaused = true;
        if (tts != null) tts.stop();
        if (externalTtsPlayer != null) externalTtsPlayer.stop();
        if (bgPlayer != null) bgPlayer.pause();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void applyTtsSettings() {
        if (tts == null) return;
        float rate = 0.5f + (prefs.getInt("ttsRate", 75) / 100f);
        float pitch = 0.5f + (prefs.getInt("ttsPitch", 50) / 100f);
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        String savedVoice = prefs.getString("ttsVoice", "");
        Set<Voice> voices = tts.getVoices();
        if (!savedVoice.isEmpty() && voices != null) {
            for (Voice voice : voices) {
                if (savedVoice.equals(voice.getName())) {
                    tts.setVoice(voice);
                    break;
                }
            }
        }
    }

    private void startBackgroundMusic() {
        if (bgPlayer == null) return;
        if (bgPlayer.getMediaItemCount() == 0) prepareBackgroundMusicQueue();
        if (bgPlayer.getMediaItemCount() == 0) return;
        applyBackgroundMusicVolume();
        bgPlayer.play();
    }

    private void prepareBackgroundMusicQueue() {
        if (bgPlayer == null) return;
        List<String> uris = loadBackgroundMusicUris();
        bgPlayer.clearMediaItems();
        for (String raw : uris) bgPlayer.addMediaItem(MediaItem.fromUri(Uri.parse(raw)));
        if (!uris.isEmpty()) {
            applyBackgroundRepeatMode();
            bgPlayer.prepare();
            bgPlayer.seekToDefaultPosition(selectedBackgroundIndex(uris.size()));
        }
    }

    private List<String> loadBackgroundMusicUris() {
        List<String> uris = new ArrayList<>();
        String raw = prefs.getString("bgPlaylist", "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String uri = array.optString(i, "");
                    if (!uri.isEmpty() && !uris.contains(uri)) uris.add(uri);
                }
            } catch (JSONException ignored) {
            }
        }
        String legacy = prefs.getString("ttsBgUri", "");
        if (!legacy.isEmpty() && !uris.contains(legacy)) uris.add(legacy);
        return uris;
    }

    private void applyBackgroundMusicVolume() {
        if (bgPlayer == null) return;
        bgPlayer.setVolume(prefs.getInt("bgVolume", 25) / 100f);
    }

    private void updateBackgroundMusicFromPrefs() {
        if (bgPlayer == null) return;
        List<String> uris = loadBackgroundMusicUris();
        boolean wasPlaying = bgPlayer.isPlaying();
        bgPlayer.clearMediaItems();
        for (String raw : uris) bgPlayer.addMediaItem(MediaItem.fromUri(Uri.parse(raw)));
        applyBackgroundRepeatMode();
        if (!uris.isEmpty()) {
            bgPlayer.prepare();
            bgPlayer.seekToDefaultPosition(selectedBackgroundIndex(uris.size()));
            if (wasPlaying) bgPlayer.play();
        }
        applyBackgroundMusicVolume();
    }

    private int selectedBackgroundIndex(int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(prefs.getInt("bgSelectedIndex", 0), size - 1));
    }

    private void applyBackgroundRepeatMode() {
        if (bgPlayer == null) return;
        bgPlayer.setRepeatMode(prefs.getBoolean("bgRepeatOne", false)
                ? androidx.media3.common.Player.REPEAT_MODE_ONE
                : androidx.media3.common.Player.REPEAT_MODE_ALL);
    }

    private void updateNotification(boolean reading) {
        Notification notification = buildNotification(reading);
        if (reading) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_DETACH);
        }
    }

    private Notification buildNotification(boolean reading) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 30, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String progress = chunks.isEmpty() ? "准备朗读" : "第 " + Math.min(currentChunk + 1, chunks.size()) + "/" + chunks.size() + " 段";
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle(title)
                .setContentText((reading ? "正在朗读 · " : "已暂停 · ") + progress)
                .setSmallIcon(R.drawable.ic_stat_dongting)
                .setContentIntent(contentIntent)
                .setOngoing(reading)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "上一段", action(ACTION_PREVIOUS, 33))
                .addAction(reading ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        reading ? "暂停" : "继续", action(ACTION_TOGGLE, 31))
                .addAction(android.R.drawable.ic_media_next, "下一段", action(ACTION_NEXT, 34))
                .addAction(android.R.drawable.ic_menu_revert, "从头读", action(ACTION_RESTART, 35))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", action(ACTION_STOP, 32));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setChannelId(CHANNEL_ID);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        return builder.build();
    }

    private PendingIntent action(String action, int requestCode) {
        Intent intent = new Intent(this, TextReaderService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "地洞朗读",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("地洞播放器 TXT 后台朗读控制");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private String readText(Uri uri) {
        if (isEpubUri(uri)) return readEpubText(uri);
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return "";
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            String text = MediaUtils.decodeTextBytes(output.toByteArray());
            return isJsonUri(uri) ? jsonToReadableText(text) : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isJsonUri(Uri uri) {
        String mime = getContentResolver().getType(uri);
        String name = readDisplayName(uri).toLowerCase(Locale.ROOT);
        return "application/json".equals(mime) || name.endsWith(".json");
    }

    private boolean isEpubUri(Uri uri) {
        String mime = getContentResolver().getType(uri);
        String name = readDisplayName(uri).toLowerCase(Locale.ROOT);
        return "application/epub+zip".equals(mime) || name.endsWith(".epub");
    }

    private String readEpubText(Uri uri) {
        StringBuilder text = new StringBuilder();
        try (InputStream input = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".txt"))) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) output.write(buffer, 0, read);
                text.append('\n').append(stripHtml(MediaUtils.decodeTextBytes(output.toByteArray())));
            }
        } catch (Exception ignored) {
        }
        return text.toString().trim();
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>|</h[1-6]>|</div>|</li>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String jsonToReadableText(String raw) {
        StringBuilder builder = new StringBuilder();
        try {
            String trimmed = raw.trim();
            Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
            appendJsonValue(builder, root, "");
            String result = builder.toString().trim();
            return result.isEmpty() ? raw : result;
        } catch (JSONException ignored) {
            return raw;
        }
    }

    private void appendJsonValue(StringBuilder builder, Object value, String prefix) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                appendJsonValue(builder, object.get(key), key);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) appendJsonValue(builder, array.get(i), prefix);
        } else if (value != null && value != JSONObject.NULL) {
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                if (!prefix.isEmpty()) builder.append(prefix).append("：");
                builder.append(text).append('\n');
            }
        }
    }

    private String readDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String name = uri.getLastPathSegment();
        return name == null || name.isEmpty() ? "TXT 朗读" : name;
    }

    private List<String> splitTextForTts(String text) {
        List<String> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder builder = new StringBuilder();
        int softLimit = useExternalTts() ? 90 : 240;
        int hardLimit = useExternalTts() ? EXTERNAL_TTS_MAX_CHARS : 480;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            builder.append(ch);
            boolean boundary = ch == '\n' || ch == '。' || ch == '；' || ch == '！' || ch == '？' || ch == '，'
                    || ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ',';
            if (builder.length() >= softLimit && boundary) {
                result.add(builder.toString().trim());
                builder.setLength(0);
            } else if (builder.length() >= hardLimit) {
                result.add(builder.toString().trim());
                builder.setLength(0);
            }
        }
        if (builder.length() > 0) result.add(builder.toString().trim());
        if (result.isEmpty()) result.add(normalized);
        return result;
    }
}
