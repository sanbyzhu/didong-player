package com.dongting.player;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class NowPlayingActivity extends AppCompatActivity {
    private static final int COLOR_BG = 0xFF160E09;
    private static final int COLOR_PANEL = 0xFF26170E;
    private static final int COLOR_TEXT = 0xFFFFF4E2;
    private static final int COLOR_SUBTLE = 0xFFD7B98E;
    private static final int COLOR_ACCENT = 0xFFFFB451;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlaybackService playbackService;
    private ExoPlayer player;
    private TextView title;
    private TextView subtitle;
    private TextView time;
    private TextView speed;
    private TextView mode;
    private TextView visual;
    private SeekBar seekBar;
    private Button playButton;
    private SharedPreferences prefs;
    private boolean dragging;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((PlaybackService.LocalBinder) service).getService();
            player = playbackService.getPlayer();
            updateUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            player = null;
            playbackService = null;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("dongting_player", MODE_PRIVATE);
        setContentView(buildContent());
        bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(ticker);
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(COLOR_BG);

        title = label("洞听播放器", 24, COLOR_TEXT);
        title.setGravity(Gravity.CENTER);
        subtitle = label("正在播放", 14, COLOR_SUBTLE);
        subtitle.setGravity(Gravity.CENTER);
        visual = label("耳朵在树洞里听见了声音", 18, COLOR_ACCENT);
        visual.setGravity(Gravity.CENTER);
        visual.setBackgroundColor(0xFF2B160A);
        visual.setPadding(dp(12), dp(42), dp(12), dp(42));

        time = label("00:00 / 00:00", 14, COLOR_SUBTLE);
        time.setGravity(Gravity.CENTER);
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && isReadingMode()) {
                    time.setText(readingProgressTextForProgress(progress));
                    return;
                }
                if (fromUser && player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    long target = player.getDuration() * progress / 1000L;
                    time.setText(MediaUtils.formatMs(target) + " / " + MediaUtils.formatMs(player.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (isReadingMode()) {
                    seekReadingByProgress(bar.getProgress());
                    dragging = false;
                    return;
                }
                if (player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * bar.getProgress() / 1000L);
                    savePosition();
                }
                dragging = false;
            }
        });
        playButton = btn("播放/暂停", v -> {
            if (isReadingMode()) {
                sendTextReaderAction(TextReaderService.ACTION_TOGGLE);
                updateUi();
                return;
            }
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
            updateUi();
        });
        speed = label("1.00x", 14, COLOR_SUBTLE);
        speed.setGravity(Gravity.CENTER);
        mode = label("顺序播放", 14, COLOR_SUBTLE);
        mode.setGravity(Gravity.CENTER);

        root.addView(title);
        root.addView(subtitle);
        root.addView(visual, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(time);
        root.addView(seekBar);
        root.addView(row(
                btn("上一首", v -> {
                    if (isReadingMode()) {
                        sendTextReaderAction(TextReaderService.ACTION_PREVIOUS);
                    } else if (player != null && player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem();
                    }
                    updateUi();
                }),
                playButton,
                btn("下一首", v -> {
                    if (isReadingMode()) {
                        sendTextReaderAction(TextReaderService.ACTION_NEXT);
                    } else if (player != null && player.hasNextMediaItem()) {
                        player.seekToNextMediaItem();
                    }
                    updateUi();
                })
        ));
        root.addView(row(
                btn("快退15秒", v -> seekBy(-15000)),
                btn("快进30秒", v -> seekBy(30000)),
                btn("回播放器", v -> finish())
        ));
        root.addView(row(
                btn("减速", v -> changeSpeed(-0.25f)),
                btn("加速", v -> changeSpeed(0.25f)),
                btn("循环模式", v -> cycleRepeatMode())
        ));
        root.addView(row(
                btn("\u76ee\u5f55", v -> showReadingCatalogDialog()),
                btn("\u4e66\u7b7e", v -> showReadingBookmarksDialog()),
                btn("\u52a0\u4e66\u7b7e", v -> addReadingBookmark())
        ));
        root.addView(row(
                btn("\u641c\u7d22", v -> showReadingSearchDialog()),
                btn("\u4e0a\u4e00\u6bb5", v -> seekReadingByDelta(-1)),
                btn("\u4e0b\u4e00\u6bb5", v -> seekReadingByDelta(1))
        ));
        root.addView(speed);
        root.addView(mode);
        return root;
    }

    private void seekBy(long delta) {
        if (isReadingMode()) {
            seekReadingByDelta(delta < 0 ? -1 : 1);
            return;
        }
        if (player == null) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + delta);
        if (duration > 0 && duration != C.TIME_UNSET) target = Math.min(duration, target);
        player.seekTo(target);
        savePosition();
        updateUi();
    }

    private void changeSpeed(float delta) {
        if (isReadingMode()) return;
        if (player == null) return;
        float next = Math.max(0.25f, Math.min(8f, player.getPlaybackParameters().speed + delta));
        player.setPlaybackParameters(new PlaybackParameters(next, 1f));
        prefs.edit().putFloat("lastSpeed", next).apply();
        updateUi();
    }

    private void cycleRepeatMode() {
        if (isReadingMode()) return;
        if (player == null) return;
        if (player.getRepeatMode() == Player.REPEAT_MODE_OFF) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
        } else if (player.getRepeatMode() == Player.REPEAT_MODE_ONE) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        } else {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        }
        updateUi();
    }

    private void savePosition() {
        if (player == null || prefs == null || player.getCurrentMediaItem() == null) return;
        MediaItem item = player.getCurrentMediaItem();
        if (item.localConfiguration == null) return;
        Uri uri = item.localConfiguration.uri;
        prefs.edit()
                .putString("currentUri", uri.toString())
                .putLong("pos:" + uri, player.getCurrentPosition())
                .putInt("currentIndex", player.getCurrentMediaItemIndex())
                .apply();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 500);
        }
    };

    private void updateUi() {
        if (isReadingMode()) {
            updateReadingUi();
            return;
        }
        if (player == null) return;
        if (player.getCurrentMediaItem() != null) {
            CharSequence t = player.getCurrentMediaItem().mediaMetadata.title;
            CharSequence s = player.getCurrentMediaItem().mediaMetadata.artist;
            title.setText(t == null ? "洞听播放器" : t);
            subtitle.setText(s == null ? "" : s);
        }
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        time.setText(MediaUtils.formatMs(position) + " / " + MediaUtils.formatMs(duration));
        if (!dragging && duration > 0 && duration != C.TIME_UNSET) seekBar.setProgress((int) Math.max(0, Math.min(1000, position * 1000L / duration)));
        playButton.setText(player.isPlaying() ? "暂停" : "播放");
        speed.setText(String.format(Locale.CHINA, "播放速度 %.2fx", player.getPlaybackParameters().speed));
        String repeat = player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "单曲循环"
                : player.getRepeatMode() == Player.REPEAT_MODE_ALL ? "列表循环" : "顺序播放";
        mode.setText(repeat);
        if (visual != null) visual.setText(nowPlayingBody(position));
    }

    private boolean isReadingMode() {
        return TextReaderService.isRunning() && (player == null || !player.isPlaying());
    }

    private void updateReadingUi() {
        String uri = prefs.getString("lastTextUri", "");
        String name = uri.isEmpty() ? "TXT 朗读" : displayName(Uri.parse(uri));
        title.setText(name);
        subtitle.setText(TextReaderService.isPaused() ? "TXT朗读 · 已暂停" : "TXT朗读 · 正在朗读");
        time.setText(readingProgressText(uri));
        if (!dragging) seekBar.setProgress(readingProgressValue(uri));
        playButton.setText(TextReaderService.isPaused() ? "继续" : "暂停");
        speed.setText("朗读由系统 TTS 控制");
        mode.setText("上一首/下一首 = 上一段/下一段");
        if (visual != null) visual.setText(currentReadingChunk());
    }

    private String readingProgressText(String uri) {
        if (uri == null || uri.isEmpty()) return "TXT 朗读";
        String text = readText(Uri.parse(uri));
        List<String> chunks = splitTextForTts(text);
        if (chunks.isEmpty()) return "TXT 朗读";
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        return "第 " + (index + 1) + " / " + chunks.size() + " 段";
    }

    private String readingProgressTextForProgress(int progress) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return "\u0054\u0058\u0054 \u6717\u8bfb";
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return "\u0054\u0058\u0054 \u6717\u8bfb";
        int index = Math.max(0, Math.min(chunks.size() - 1, progress * chunks.size() / 1001));
        return "\u7b2c " + (index + 1) + " / " + chunks.size() + " \u6bb5";
    }

    private int readingProgressValue(String uri) {
        if (uri == null || uri.isEmpty()) return 0;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return 0;
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        return (int) Math.max(0, Math.min(1000, (index * 1000L) / Math.max(1, chunks.size() - 1)));
    }

    private void seekReadingByProgress(int progress) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return;
        int index = Math.max(0, Math.min(chunks.size() - 1, progress * chunks.size() / 1001));
        seekReadingChunk(index);
    }

    private void seekReadingByDelta(int delta) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return;
        int current = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        seekReadingChunk(current + delta);
    }

    private void seekReadingChunk(int index) {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return;
        int safe = Math.max(0, Math.min(chunks.size() - 1, index));
        prefs.edit()
                .putInt("ttsChunk:" + uri, safe)
                .putInt("ttsRangeStart:" + uri, 0)
                .putInt("ttsRangeEnd:" + uri, 0)
                .apply();
        startService(new Intent(this, TextReaderService.class)
                .setAction(TextReaderService.ACTION_SEEK_CHUNK)
                .putExtra(TextReaderService.EXTRA_CHUNK_INDEX, safe));
        updateUi();
    }

    private void sendTextReaderAction(String action) {
        startService(new Intent(this, TextReaderService.class).setAction(action));
    }

    private String nowPlayingBody(long positionMs) {
        String mediaUri = currentMediaUri();
        String timed = currentTimedText(mediaUri, positionMs);
        if (!timed.isEmpty()) return timed;
        if (TextReaderService.isRunning() && (player == null || !player.isPlaying())) {
            String reading = currentReadingChunk();
            if (!reading.isEmpty()) return reading;
        }
        if (player != null && player.getCurrentMediaItem() != null) {
            CharSequence t = player.getCurrentMediaItem().mediaMetadata.title;
            return (t == null ? "洞听播放器" : t) + "\n暂无歌词";
        }
        return "耳朵在树洞里听见了声音";
    }

    private String currentMediaUri() {
        if (player == null || player.getCurrentMediaItem() == null) return "";
        MediaItem item = player.getCurrentMediaItem();
        if (item.localConfiguration == null) return "";
        return item.localConfiguration.uri.toString();
    }

    private void showReadingCatalogDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        if (chunks.isEmpty()) return;
        List<Integer> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunkPreview(chunks.get(i));
            if (looksLikeChapter(preview) || labels.size() < 80) {
                targets.add(i);
                labels.add((i + 1) + ". " + preview);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("\u76ee\u5f55")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> seekReadingChunk(targets.get(which)))
                .setNegativeButton("\u5173\u95ed", null)
                .show();
    }

    private void addReadingBookmark() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        int index = prefs.getInt("ttsChunk:" + uri, 0);
        JSONArray array = loadReadingBookmarks(uri);
        for (int i = 0; i < array.length(); i++) {
            if (array.optInt(i, -1) == index) return;
        }
        array.put(index);
        prefs.edit().putString("textMarks:" + uri, array.toString()).apply();
    }

    private void showReadingBookmarksDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        JSONArray marks = loadReadingBookmarks(uri);
        if (chunks.isEmpty() || marks.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("\u4e66\u7b7e")
                    .setMessage("\u6682\u65e0\u4e66\u7b7e")
                    .setPositiveButton("\u786e\u5b9a", null)
                    .show();
            return;
        }
        List<Integer> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < marks.length(); i++) {
            int target = Math.max(0, Math.min(chunks.size() - 1, marks.optInt(i, 0)));
            targets.add(target);
            labels.add((target + 1) + ". " + chunkPreview(chunks.get(target)));
        }
        new AlertDialog.Builder(this)
                .setTitle("\u4e66\u7b7e")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> seekReadingChunk(targets.get(which)))
                .setNeutralButton("\u6e05\u7a7a", (dialog, which) -> prefs.edit().remove("textMarks:" + uri).apply())
                .setNegativeButton("\u5173\u95ed", null)
                .show();
    }

    private void showReadingSearchDialog() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri == null || uri.isEmpty()) return;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("\u8f93\u5165\u8981\u641c\u7d22\u7684\u6587\u5b57");
        new AlertDialog.Builder(this)
                .setTitle("\u641c\u7d22\u6587\u672c")
                .setView(input)
                .setPositiveButton("\u641c\u7d22", (dialog, which) -> showReadingSearchResults(uri, input.getText().toString()))
                .setNegativeButton("\u53d6\u6d88", null)
                .show();
    }

    private void showReadingSearchResults(String uri, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;
        List<String> chunks = splitTextForTts(readText(Uri.parse(uri)));
        List<Integer> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < chunks.size() && labels.size() < 50; i++) {
            if (chunks.get(i).contains(q)) {
                targets.add(i);
                labels.add((i + 1) + ". " + chunkPreview(chunks.get(i)));
            }
        }
        if (labels.isEmpty()) labels.add("\u6ca1\u6709\u627e\u5230");
        new AlertDialog.Builder(this)
                .setTitle("\u641c\u7d22\u7ed3\u679c")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (!targets.isEmpty()) seekReadingChunk(targets.get(which));
                })
                .setNegativeButton("\u5173\u95ed", null)
                .show();
    }

    private JSONArray loadReadingBookmarks(String uri) {
        try {
            return new JSONArray(prefs.getString("textMarks:" + uri, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private boolean looksLikeChapter(String text) {
        String value = text == null ? "" : text.trim();
        return value.startsWith("\u7b2c") && (value.contains("\u7ae0") || value.contains("\u8282") || value.contains("\u56de"))
                || value.startsWith("Chapter ") || value.startsWith("chapter ");
    }

    private String chunkPreview(String text) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() > 42 ? value.substring(0, 42) : value;
    }

    private String currentTimedText(String mediaUri, long positionMs) {
        if (mediaUri.isEmpty()) return "";
        long offset = prefs.getLong("timedOffset:" + mediaUri, 0);
        String raw = prefs.getString("timed:" + mediaUri, "[]");
        long adjusted = positionMs - offset;
        String current = "";
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                long timeMs = obj.optLong("t", 0);
                if (timeMs <= adjusted + 250) current = obj.optString("x", ""); else break;
            }
        } catch (JSONException ignored) {
        }
        return current == null ? "" : current.trim();
    }

    private String currentReadingChunk() {
        String uri = prefs.getString("lastTextUri", "");
        if (uri.isEmpty()) return "";
        String text = readText(Uri.parse(uri));
        if (text.trim().isEmpty()) return "";
        List<String> chunks = splitTextForTts(text);
        if (chunks.isEmpty()) return "";
        int index = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + uri, 0), chunks.size() - 1));
        boolean chunkMode = "chunk".equals(prefs.getString("ttsVisualMode:" + uri, ""));
        int start = chunkMode ? -1 : prefs.getInt("ttsRangeStart:" + uri, -1);
        int end = chunkMode ? -1 : prefs.getInt("ttsRangeEnd:" + uri, -1);
        return readingWindowText(chunks, index, start, end);
    }

    private String readingWindowText(List<String> chunks, int index, int rangeStart, int rangeEnd) {
        if (chunks == null || chunks.isEmpty()) return "";
        index = Math.max(0, Math.min(index, chunks.size() - 1));
        StringBuilder builder = new StringBuilder();
        builder.append("朗读文字  ").append(index + 1).append("/").append(chunks.size()).append('\n');
        builder.append("▶ ").append(readingCurrentPreview(chunks.get(index), rangeStart, rangeEnd));
        return builder.toString();
    }

    private String readingCurrentPreview(String text, int rangeStart, int rangeEnd) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        if (rangeStart < 0 || rangeStart >= value.length()) return value;
        rangeEnd = Math.max(rangeStart + 1, Math.min(rangeEnd, value.length()));
        String before = value.substring(0, rangeStart);
        String current = value.substring(rangeStart, rangeEnd);
        String after = value.substring(rangeEnd);
        return before + "【" + current + "】" + after;
    }

    private List<String> splitTextForTts(String text) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            builder.append(ch);
            boolean boundary = ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?';
            if (builder.length() >= 240 && boundary) {
                chunks.add(builder.toString().trim());
                builder.setLength(0);
            } else if (builder.length() >= 480) {
                chunks.add(builder.toString().trim());
                builder.setLength(0);
            }
        }
        if (builder.length() > 0) chunks.add(builder.toString().trim());
        if (chunks.isEmpty()) chunks.add(normalized);
        return chunks;
    }

    private String readText(Uri uri) {
        if (isEpubUri(uri)) return readEpubText(uri);
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return "";
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            String text = output.toString("UTF-8");
            return isJsonUri(uri) ? jsonToReadableText(text) : text;
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isJsonUri(Uri uri) {
        String mime = getContentResolver().getType(uri);
        String name = displayName(uri).toLowerCase(Locale.ROOT);
        return "application/json".equals(mime) || name.endsWith(".json");
    }

    private boolean isEpubUri(Uri uri) {
        String mime = getContentResolver().getType(uri);
        String name = displayName(uri).toLowerCase(Locale.ROOT);
        return "application/epub+zip".equals(mime) || name.endsWith(".epub");
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String name = uri.getLastPathSegment();
        return name == null ? "" : name;
    }

    private String readEpubText(Uri uri) {
        StringBuilder text = new StringBuilder();
        try (InputStream input = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !(name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".txt"))) continue;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) output.write(buffer, 0, read);
                text.append('\n').append(stripHtml(output.toString("UTF-8")));
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
            Object root = raw.trim().startsWith("[") ? new JSONArray(raw.trim()) : new JSONObject(raw.trim());
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

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    private LinearLayout row(android.view.View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (android.view.View view : views) row.addView(view, new LinearLayout.LayoutParams(0, dp(44), 1f));
        return row;
    }

    private Button btn(String text, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setBackgroundColor(COLOR_PANEL);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
