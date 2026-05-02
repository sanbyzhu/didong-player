package com.dongting.player;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final String PREFS = "dongting_player";
    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_PANEL = 0xFF1A2027;
    private static final int COLOR_TEXT = 0xFFF3F6F8;
    private static final int COLOR_SUBTLE = 0xFFAAB4BF;
    private static final int COLOR_ACCENT = 0xFF35C2A6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaEntry> library = new ArrayList<>();
    private final List<MediaEntry> queue = new ArrayList<>();
    private final Map<String, List<MediaEntry>> playlists = new HashMap<>();
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    private SharedPreferences prefs;
    private ExoPlayer player;
    private ExoPlayer bgPlayer;
    private LoudnessEnhancer loudnessEnhancer;
    private TextToSpeech tts;

    private PlayerView playerView;
    private ListView mediaList;
    private ArrayAdapter<String> mediaAdapter;
    private Spinner playlistSpinner;
    private Spinner voiceSpinner;
    private TextView nowPlaying;
    private TextView status;
    private TextView positionLabel;
    private TextView speedLabel;
    private TextView loopLabel;
    private Button playPauseButton;
    private SeekBar positionBar;
    private SeekBar speedBar;
    private SeekBar volumeBar;
    private SeekBar boostBar;
    private SeekBar bgVolumeBar;

    private int currentIndex = -1;
    private int loopMode = 0;
    private long abA = C.TIME_UNSET;
    private long abB = C.TIME_UNSET;
    private boolean abEnabled = false;
    private boolean draggingPosition = false;
    private boolean suppressPositionSave = false;
    private String selectedPlaylist = "默认列表";
    private String importedText = "";
    private final List<Voice> voices = new ArrayList<>();

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(
                                uri,
                                result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                        scanFolder(uri);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> textPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    importedText = readText(result.getData().getData());
                    status("已导入文本：" + importedText.length() + " 字");
                }
            });

    private final ActivityResultLauncher<Intent> backgroundPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    startBackgroundMusic(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setupPlayers();
        setupUi();
        loadPlaylists();
        tts = new TextToSpeech(this, this);
    }

    private void setupPlayers() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        player = new ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build();
        bgPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, false).build();
        bgPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    attachLoudnessEnhancer();
                    updatePositionUi();
                } else if (playbackState == Player.STATE_ENDED) {
                    handleEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseButton();
            }
        });
        handler.post(abLoopTicker);
        handler.post(positionTicker);
    }

    private void setupUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));

        nowPlaying = label("洞听播放器", 20, COLOR_TEXT);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying.setOnClickListener(v -> togglePlay());
        root.addView(nowPlaying);

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setBackgroundColor(0xFF050708);
        root.addView(playerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        positionLabel = label("00:00 / 00:00", 13, COLOR_SUBTLE);
        positionLabel.setGravity(Gravity.CENTER);
        root.addView(positionLabel);

        positionBar = new SeekBar(this);
        positionBar.setMax(1000);
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    long target = player.getDuration() * progress / 1000L;
                    positionLabel.setText(formatMs(target) + " / " + formatMs(player.getDuration()));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                draggingPosition = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * seekBar.getProgress() / 1000L);
                }
                draggingPosition = false;
                updatePositionUi();
            }
        });
        root.addView(positionBar);

        root.addView(row(
                btn("扫描文件夹", v -> pickFolder()),
                btn("新建列表", v -> createPlaylist()),
                btn("加入列表", v -> addLibraryToPlaylist()),
                btn("投屏", v -> status("二期功能：将接入 MediaRouter/Cast/DLNA，支持仅投画面或音画同投。"))
        ));

        LinearLayout playbackRow = row(
                btn("上一首", v -> playRelative(-1)),
                btn("播放", v -> togglePlay()),
                btn("下一首", v -> playRelative(1)),
                btn("设置", v -> openAndroidSoundSettings())
        );
        playPauseButton = (Button) playbackRow.getChildAt(1);
        playPauseButton.setTextSize(18);
        playPauseButton.setTextColor(0xFF101418);
        playPauseButton.setBackgroundColor(COLOR_ACCENT);
        root.addView(playbackRow);

        playlistSpinner = new Spinner(this);
        root.addView(playlistSpinner, fullWrap());
        playlistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPlaylist = String.valueOf(parent.getItemAtPosition(position));
                List<MediaEntry> items = playlists.get(selectedPlaylist);
                if (items != null && !items.isEmpty()) {
                    setQueue(items, false);
                    status("已切换列表：" + selectedPlaylist + "，" + items.size() + " 个文件");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        speedLabel = label("", 14, COLOR_SUBTLE);
        speedBar = new SeekBar(this);
        speedBar.setMax(775);
        speedBar.setProgress(Math.round((prefs.getFloat("lastSpeed", 1f) - 0.25f) * 100f));
        speedBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            float speed = (progress + 25) / 100f;
            setSpeed(speed, true);
        }));
        root.addView(speedLabel);
        root.addView(speedBar);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(prefs.getInt("volume", 100));
        volumeBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            player.setVolume(progress / 100f);
            prefs.edit().putInt("volume", progress).apply();
        }));
        root.addView(label("播放音量", 14, COLOR_SUBTLE));
        root.addView(volumeBar);

        boostBar = new SeekBar(this);
        boostBar.setMax(1500);
        boostBar.setProgress(prefs.getInt("boost", 0));
        boostBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("boost", progress).apply();
            applyBoost();
        }));
        root.addView(label("无损增益（不改源文件）", 14, COLOR_SUBTLE));
        root.addView(boostBar);

        loopLabel = label("", 14, COLOR_SUBTLE);
        root.addView(loopLabel);
        root.addView(row(
                btn("循环模式", v -> cycleLoop()),
                btn("A点", v -> setA()),
                btn("B点", v -> setB()),
                btn("清AB", v -> clearAb())
        ));

        root.addView(row(
                btn("导入TXT", v -> pickText()),
                btn("朗读/暂停", v -> speakText()),
                btn("停止朗读", v -> stopSpeaking()),
                btn("背景音乐", v -> pickBackground())
        ));
        voiceSpinner = new Spinner(this);
        root.addView(voiceSpinner, fullWrap());
        bgVolumeBar = new SeekBar(this);
        bgVolumeBar.setMax(100);
        bgVolumeBar.setProgress(prefs.getInt("bgVolume", 25));
        bgVolumeBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            bgPlayer.setVolume(progress / 100f);
            prefs.edit().putInt("bgVolume", progress).apply();
        }));
        root.addView(label("朗读背景音量", 14, COLOR_SUBTLE));
        root.addView(bgVolumeBar);

        status = label("请选择文件夹开始扫描", 14, COLOR_SUBTLE);
        root.addView(status);

        mediaList = new ListView(this);
        mediaList.setBackgroundColor(COLOR_PANEL);
        mediaAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        mediaList.setAdapter(mediaAdapter);
        mediaList.setOnItemClickListener((parent, view, position, id) -> {
            if (position == currentIndex) {
                togglePlay();
            } else {
                playAt(position);
            }
        });
        root.addView(mediaList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        setSpeed((speedBar.getProgress() + 25) / 100f, false);
        player.setVolume(volumeBar.getProgress() / 100f);
        bgPlayer.setVolume(bgVolumeBar.getProgress() / 100f);
        updateLoopLabel();
        updatePlayPauseButton();
        updatePositionUi();
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPicker.launch(intent);
    }

    private void scanFolder(Uri folderUri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, folderUri);
        if (root == null || !root.exists()) {
            status("无法读取该文件夹");
            return;
        }
        library.clear();
        Map<String, List<MediaEntry>> folderBuckets = new LinkedHashMap<>();
        Map<String, String> folderNames = new HashMap<>();
        scanInto(root, root.getUri().toString(), library, folderBuckets, folderNames);
        Collections.sort(library, this::compareMedia);
        playlists.put("全部文件", new ArrayList<>(library));
        for (Map.Entry<String, List<MediaEntry>> bucket : folderBuckets.entrySet()) {
            Collections.sort(bucket.getValue(), this::compareMedia);
            playlists.put("文件夹：" + bucket.getKey(), new ArrayList<>(bucket.getValue()));
        }
        savePlaylists();
        selectedPlaylist = "全部文件";
        refreshPlaylistSpinner();
        setQueue(library, true);
        prefs.edit().putString("lastFolder", folderUri.toString()).apply();
        status("扫描完成：" + library.size() + " 个音视频文件，已自动生成 " + folderBuckets.size() + " 个文件夹列表");
    }

    private void scanInto(DocumentFile dir, String folderKey, List<MediaEntry> output, Map<String, List<MediaEntry>> folderBuckets, Map<String, String> folderNames) {
        DocumentFile[] files = dir.listFiles();
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                scanInto(file, file.getUri().toString(), output, folderBuckets, folderNames);
            } else if (file.isFile() && isMedia(file.getName())) {
                String baseName = dir.getName() == null ? "未命名文件夹" : dir.getName();
                String folderName = folderNames.computeIfAbsent(folderKey, key -> uniqueFolderName(folderBuckets, baseName));
                MediaEntry entry = new MediaEntry(
                        file.getUri().toString(),
                        file.getName() == null ? "未命名" : file.getName(),
                        folderName,
                        folderKey,
                        isVideo(file.getName()) ? "video" : "audio"
                );
                output.add(entry);
                folderBuckets.computeIfAbsent(folderName, key -> new ArrayList<>()).add(entry);
            }
        }
    }

    private void setQueue(List<MediaEntry> source, boolean showLibrary) {
        List<MediaEntry> snapshot = new ArrayList<>(source);
        queue.clear();
        queue.addAll(snapshot);
        refreshMediaList();
        if (showLibrary && !queue.isEmpty()) {
            status("可点选文件播放；再次点当前播放项可暂停/继续");
        }
    }

    private void refreshMediaList() {
        mediaAdapter.clear();
        for (int i = 0; i < queue.size(); i++) {
            MediaEntry entry = queue.get(i);
            String prefix = i == currentIndex ? "正在播放  " : "";
            mediaAdapter.add(prefix + (entry.type.equals("video") ? "[视频] " : "[音频] ") + entry.title + "\n" + entry.folderName);
        }
        mediaAdapter.notifyDataSetChanged();
    }

    private void playAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        saveCurrentPosition();
        currentIndex = index;
        MediaEntry entry = queue.get(index);
        suppressPositionSave = false;
        nowPlaying.setText(entry.title + "\n" + entry.folderName);
        player.setMediaItem(MediaItem.fromUri(Uri.parse(entry.uri)));
        player.prepare();
        long last = prefs.getLong("pos:" + entry.uri, 0);
        if (last > 0) player.seekTo(last);
        float folderSpeed = prefs.getFloat(speedKey(entry), prefs.getFloat("lastSpeed", 1f));
        speedBar.setProgress(Math.round((folderSpeed - 0.25f) * 100f));
        setSpeed(folderSpeed, false);
        loadAb(entry.uri);
        player.play();
        refreshMediaList();
        updatePlayPauseButton();
        updatePositionUi();
        status("正在播放：" + entry.folderName);
    }

    private String uniqueFolderName(Map<String, List<MediaEntry>> folderBuckets, String baseName) {
        String candidate = baseName;
        int index = 2;
        while (folderBuckets.containsKey(candidate)) {
            candidate = baseName + " (" + index + ")";
            index++;
        }
        return candidate;
    }

    private void togglePlay() {
        if (currentIndex < 0 && !queue.isEmpty()) {
            playAt(0);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            saveCurrentPosition();
        } else {
            player.play();
        }
        updatePlayPauseButton();
    }

    private void playRelative(int delta) {
        if (queue.isEmpty()) return;
        int next = currentIndex < 0 ? 0 : currentIndex + delta;
        if (next < 0) next = queue.size() - 1;
        if (next >= queue.size()) next = 0;
        playAt(next);
    }

    private void handleEnded() {
        saveCurrentPositionAsZero();
        if (loopMode == 1) {
            player.seekTo(0);
            player.play();
        } else if (loopMode == 2 && !queue.isEmpty()) {
            playRelative(1);
        } else {
            updatePlayPauseButton();
        }
    }

    private final Runnable positionTicker = new Runnable() {
        @Override public void run() {
            updatePositionUi();
            handler.postDelayed(this, 500);
        }
    };

    private void updatePositionUi() {
        if (positionBar == null || positionLabel == null || draggingPosition) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        if (duration <= 0 || duration == C.TIME_UNSET) {
            positionBar.setProgress(0);
            positionLabel.setText(formatMs(position) + " / 00:00");
            return;
        }
        positionBar.setProgress((int) Math.max(0, Math.min(1000, position * 1000L / duration)));
        positionLabel.setText(formatMs(position) + " / " + formatMs(duration));
    }

    private void updatePlayPauseButton() {
        if (playPauseButton == null) return;
        playPauseButton.setText(player.isPlaying() ? "暂停" : "播放");
    }

    private void setSpeed(float speed, boolean persist) {
        float fixed = Math.max(0.25f, Math.min(8f, speed));
        speedLabel.setText(String.format(Locale.CHINA, "播放速度 %.2fx", fixed));
        player.setPlaybackParameters(new PlaybackParameters(fixed));
        if (persist) {
            SharedPreferences.Editor editor = prefs.edit().putFloat("lastSpeed", fixed);
            MediaEntry entry = currentEntry();
            if (entry != null) editor.putFloat(speedKey(entry), fixed);
            editor.apply();
        }
    }

    private String speedKey(MediaEntry entry) {
        return "speed:" + entry.folderKey + ":" + entry.type;
    }

    private void cycleLoop() {
        loopMode = (loopMode + 1) % 3;
        updateLoopLabel();
    }

    private void updateLoopLabel() {
        String text = loopMode == 0 ? "循环：关闭" : loopMode == 1 ? "循环：单曲" : "循环：列表";
        String ab = abEnabled && abA != C.TIME_UNSET && abB != C.TIME_UNSET ? " | AB：" + formatMs(abA) + "-" + formatMs(abB) : "";
        loopLabel.setText(text + ab);
    }

    private void setA() {
        abA = player.getCurrentPosition();
        abEnabled = abB != C.TIME_UNSET && abB > abA;
        persistAb();
        updateLoopLabel();
        status("A 点：" + formatMs(abA));
    }

    private void setB() {
        abB = player.getCurrentPosition();
        abEnabled = abA != C.TIME_UNSET && abB > abA;
        persistAb();
        updateLoopLabel();
        status("B 点：" + formatMs(abB));
    }

    private void clearAb() {
        MediaEntry entry = currentEntry();
        if (entry != null) prefs.edit().remove("ab:" + entry.uri).apply();
        abA = C.TIME_UNSET;
        abB = C.TIME_UNSET;
        abEnabled = false;
        updateLoopLabel();
    }

    private void loadAb(String uri) {
        abA = C.TIME_UNSET;
        abB = C.TIME_UNSET;
        abEnabled = false;
        String raw = prefs.getString("ab:" + uri, "");
        if (!raw.isEmpty()) {
            try {
                JSONObject obj = new JSONObject(raw);
                abA = obj.optLong("a", C.TIME_UNSET);
                abB = obj.optLong("b", C.TIME_UNSET);
                abEnabled = obj.optBoolean("enabled", false) && abB > abA;
                if (abEnabled) player.seekTo(abA);
            } catch (JSONException ignored) {
            }
        }
        updateLoopLabel();
    }

    private void persistAb() {
        MediaEntry entry = currentEntry();
        if (entry == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("a", abA);
            obj.put("b", abB);
            obj.put("enabled", abEnabled);
            prefs.edit().putString("ab:" + entry.uri, obj.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private final Runnable abLoopTicker = new Runnable() {
        @Override public void run() {
            if (abEnabled && abA != C.TIME_UNSET && abB != C.TIME_UNSET && player.isPlaying() && player.getCurrentPosition() >= abB) {
                player.seekTo(abA);
            }
            handler.postDelayed(this, 250);
        }
    };

    private void createPlaylist() {
        EditText input = new EditText(this);
        input.setHint("列表名称");
        new AlertDialog.Builder(this)
                .setTitle("新建播放列表")
                .setView(input)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "列表 " + (playlists.size() + 1);
                    playlists.put(name, new ArrayList<>());
                    selectedPlaylist = name;
                    savePlaylists();
                    refreshPlaylistSpinner();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addLibraryToPlaylist() {
        if (library.isEmpty()) {
            status("请先扫描文件夹");
            return;
        }
        playlists.computeIfAbsent(selectedPlaylist, key -> new ArrayList<>()).clear();
        playlists.get(selectedPlaylist).addAll(library);
        savePlaylists();
        refreshPlaylistSpinner();
        status("已加入 " + library.size() + " 个文件到：" + selectedPlaylist);
    }

    private void loadPlaylists() {
        playlists.clear();
        playlists.put("默认列表", new ArrayList<>());
        String raw = prefs.getString("playlists", "{}");
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray names = root.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    JSONArray items = root.getJSONArray(name);
                    List<MediaEntry> list = new ArrayList<>();
                    for (int j = 0; j < items.length(); j++) {
                        list.add(MediaEntry.fromJson(items.getJSONObject(j)));
                    }
                    playlists.put(name, list);
                }
            }
        } catch (JSONException ignored) {
        }
        refreshPlaylistSpinner();
    }

    private void savePlaylists() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<MediaEntry>> playlist : playlists.entrySet()) {
                JSONArray array = new JSONArray();
                for (MediaEntry item : playlist.getValue()) array.put(item.toJson());
                root.put(playlist.getKey(), array);
            }
            prefs.edit().putString("playlists", root.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private void refreshPlaylistSpinner() {
        List<String> names = new ArrayList<>(playlists.keySet());
        Collections.sort(names, collator);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        playlistSpinner.setAdapter(adapter);
        int selected = Math.max(0, names.indexOf(selectedPlaylist));
        playlistSpinner.setSelection(selected);
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.CHINA);
            refreshVoices();
        } else {
            status("系统 TTS 初始化失败");
        }
    }

    private void refreshVoices() {
        voices.clear();
        List<String> names = new ArrayList<>();
        Set<Voice> available = tts.getVoices();
        if (available != null) {
            for (Voice voice : available) {
                Locale locale = voice.getLocale();
                if (locale != null && (Locale.CHINESE.getLanguage().equals(locale.getLanguage()) || Locale.ENGLISH.getLanguage().equals(locale.getLanguage()))) {
                    voices.add(voice);
                    names.add(voice.getName() + " / " + locale.toLanguageTag());
                }
            }
        }
        if (names.isEmpty()) names.add("系统默认人声");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
        voiceSpinner.setAdapter(adapter);
    }

    private void pickText() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        textPicker.launch(intent);
    }

    private void speakText() {
        if (importedText.trim().isEmpty()) {
            status("请先导入 txt 文本");
            return;
        }
        if (tts == null) return;
        if (tts.isSpeaking()) {
            tts.stop();
            bgPlayer.pause();
            return;
        }
        int selected = voiceSpinner.getSelectedItemPosition();
        if (selected >= 0 && selected < voices.size()) tts.setVoice(voices.get(selected));
        tts.setPitch(1.0f);
        tts.setSpeechRate(1.0f);
        tts.speak(importedText, TextToSpeech.QUEUE_FLUSH, null, "dongting_text");
        if (bgPlayer.getMediaItemCount() > 0) bgPlayer.play();
        status("开始朗读文本");
    }

    private void stopSpeaking() {
        if (tts != null) tts.stop();
        bgPlayer.pause();
    }

    private void pickBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        backgroundPicker.launch(intent);
    }

    private void startBackgroundMusic(Uri uri) {
        bgPlayer.setMediaItem(MediaItem.fromUri(uri));
        bgPlayer.prepare();
        bgPlayer.setVolume(bgVolumeBar.getProgress() / 100f);
        status("已设置朗读背景音乐");
    }

    private String readText(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return "";
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString("UTF-8");
        } catch (Exception ex) {
            status("读取文本失败：" + ex.getMessage());
            return "";
        }
    }

    private void attachLoudnessEnhancer() {
        try {
            if (loudnessEnhancer != null) loudnessEnhancer.release();
            loudnessEnhancer = new LoudnessEnhancer(player.getAudioSessionId());
            loudnessEnhancer.setEnabled(true);
            applyBoost();
        } catch (RuntimeException ex) {
            status("当前设备不支持响度增强");
        }
    }

    private void applyBoost() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setTargetGain(boostBar.getProgress());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void openAndroidSoundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    private void saveCurrentPosition() {
        MediaEntry entry = currentEntry();
        if (entry != null && !suppressPositionSave) {
            prefs.edit().putLong("pos:" + entry.uri, player.getCurrentPosition()).apply();
        }
    }

    private void saveCurrentPositionAsZero() {
        MediaEntry entry = currentEntry();
        if (entry != null) prefs.edit().putLong("pos:" + entry.uri, 0).apply();
    }

    @Nullable
    private MediaEntry currentEntry() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
    }

    private int compareMedia(MediaEntry a, MediaEntry b) {
        Integer an = firstNumber(a.title);
        Integer bn = firstNumber(b.title);
        if (an != null && bn != null && !an.equals(bn)) return Integer.compare(an, bn);
        if (an != null && bn == null) return -1;
        if (an == null && bn != null) return 1;
        return collator.compare(a.title, b.title);
    }

    @Nullable
    private Integer firstNumber(String value) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(value == null ? "" : value);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private boolean isMedia(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".opus") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".mov")
                || lower.endsWith(".avi");
    }

    private boolean isVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".3gp") || lower.endsWith(".mov") || lower.endsWith(".avi");
    }

    private LinearLayout row(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (View view : views) {
            row.addView(view, new LinearLayout.LayoutParams(0, dp(42), 1f));
        }
        return row;
    }

    private Button btn(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setBackgroundColor(COLOR_PANEL);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView label(String text, int sp, int color) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sp);
        label.setTextColor(color);
        label.setPadding(0, dp(4), 0, dp(4));
        return label;
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(SeekHandler handler) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                handler.onChanged(seekBar, progress, fromUser);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private void status(String message) {
        if (status != null) status.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatMs(long ms) {
        if (ms == C.TIME_UNSET || ms < 0) return "--:--";
        long total = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentPosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        saveCurrentPosition();
        if (loudnessEnhancer != null) loudnessEnhancer.release();
        player.release();
        bgPlayer.release();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private interface SeekHandler {
        void onChanged(SeekBar bar, int progress, boolean fromUser);
    }

    private static class MediaEntry {
        final String uri;
        final String title;
        final String folderName;
        final String folderKey;
        final String type;

        MediaEntry(String uri, String title, String folderName, String folderKey, String type) {
            this.uri = uri;
            this.title = title;
            this.folderName = folderName;
            this.folderKey = folderKey;
            this.type = type;
        }

        JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("uri", uri);
            obj.put("title", title);
            obj.put("folderName", folderName);
            obj.put("folderKey", folderKey);
            obj.put("type", type);
            return obj;
        }

        static MediaEntry fromJson(JSONObject obj) {
            return new MediaEntry(
                    obj.optString("uri"),
                    obj.optString("title", "未命名"),
                    obj.optString("folderName"),
                    obj.optString("folderKey"),
                    obj.optString("type", "audio")
            );
        }
    }
}
