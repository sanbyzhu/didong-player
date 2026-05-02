package com.dongting.player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Virtualizer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
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
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
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
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final String PREFS = "dongting_player";
    private static final String PLAYLIST_DEFAULT = "默认列表";
    private static final String PLAYLIST_ALL = "全部文件";
    private static final String PLAYLIST_FAVORITES = "收藏";
    private static final String PLAYLIST_RECENT = "最近播放";
    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_PANEL = 0xFF1A2027;
    private static final int COLOR_TEXT = 0xFFF3F6F8;
    private static final int COLOR_SUBTLE = 0xFFAAB4BF;
    private static final int COLOR_ACCENT = 0xFF35C2A6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaEntry> library = new ArrayList<>();
    private final List<MediaEntry> queue = new ArrayList<>();
    private final List<Integer> visibleQueueIndexes = new ArrayList<>();
    private final List<View> fullscreenHiddenViews = new ArrayList<>();
    private final Map<String, List<MediaEntry>> playlists = new HashMap<>();
    private final Collator collator = Collator.getInstance(Locale.CHINA);
    private final Random random = new Random();

    private SharedPreferences prefs;
    private PlaybackService playbackService;
    private ExoPlayer player;
    private ExoPlayer bgPlayer;
    private MediaSession mediaSession;
    private LoudnessEnhancer loudnessEnhancer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    private TextToSpeech tts;

    private LinearLayout rootLayout;
    private LinearLayout advancedPanel;
    private PlayerView playerView;
    private ListView mediaList;
    private ArrayAdapter<String> mediaAdapter;
    private Spinner playlistSpinner;
    private Spinner voiceSpinner;
    private EditText searchBox;
    private TextView nowPlaying;
    private TextView status;
    private TextView positionLabel;
    private TextView speedLabel;
    private TextView loopLabel;
    private Button advancedToggleButton;
    private Button playPauseButton;
    private SeekBar positionBar;
    private SeekBar speedBar;
    private SeekBar volumeBar;
    private SeekBar boostBar;
    private SeekBar bassBar;
    private SeekBar stereoBar;
    private SeekBar ttsRateBar;
    private SeekBar ttsPitchBar;
    private SeekBar bgVolumeBar;

    private int currentIndex = -1;
    private int loopMode = 0;
    private long abA = C.TIME_UNSET;
    private long abB = C.TIME_UNSET;
    private boolean abEnabled = false;
    private boolean draggingPosition = false;
    private boolean fullScreenVideo = false;
    private boolean advancedVisible = false;
    private boolean suppressPositionSave = false;
    private String selectedPlaylist = PLAYLIST_DEFAULT;
    private String searchQuery = "";
    private String importedText = "";
    private int currentTextChunk = 0;
    private final List<String> textChunks = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();

    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((PlaybackService.LocalBinder) service).getService();
            player = playbackService.getPlayer();
            mediaSession = playbackService.getMediaSession();
            playerView.setPlayer(player);
            attachPlayerListener();
            restoreLastSession();
            updatePlayPauseButton();
            updatePositionUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            player = null;
            mediaSession = null;
            playerView.setPlayer(null);
        }
    };

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
        selectedPlaylist = prefs.getString("selectedPlaylist", PLAYLIST_DEFAULT);
        setupPlayers();
        setupUi();
        requestNotificationPermission();
        loadPlaylists();
        tts = new TextToSpeech(this, this);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private void setupPlayers() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();
        bgPlayer = new ExoPlayer.Builder(this).setAudioAttributes(attrs, false).build();
        bgPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        Intent serviceIntent = new Intent(this, PlaybackService.class);
        startService(serviceIntent);
        bindService(serviceIntent, playbackConnection, Context.BIND_AUTO_CREATE);
        handler.post(abLoopTicker);
        handler.post(positionTicker);
    }

    private void attachPlayerListener() {
        if (player == null) return;
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
                if (playbackService != null) playbackService.refreshNotification();
            }

            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (player == null) return;
                int index = player.getCurrentMediaItemIndex();
                if (index >= 0 && index < queue.size() && index != currentIndex) {
                    currentIndex = index;
                    MediaEntry entry = queue.get(currentIndex);
                    nowPlaying.setText(entry.title + "\n" + entry.folderName);
                    loadAb(entry.uri);
                    prefs.edit()
                            .putString("currentUri", entry.uri)
                            .putString("selectedPlaylist", selectedPlaylist)
                            .putInt("currentIndex", currentIndex)
                            .apply();
                    refreshMediaList();
                }
                updatePositionUi();
            }
        });
    }

    private void setupUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(COLOR_BG);
        rootLayout.setPadding(dp(10), dp(10), dp(10), dp(10));

        nowPlaying = label("洞听播放器", 20, COLOR_TEXT);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying.setOnClickListener(v -> togglePlay());
        rootLayout.addView(nowPlaying);

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setBackgroundColor(0xFF050708);
        playerView.setOnClickListener(v -> {
            if (currentEntry() != null && "video".equals(currentEntry().type)) toggleFullScreenVideo();
        });
        rootLayout.addView(playerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));

        positionLabel = label("00:00 / 00:00", 13, COLOR_SUBTLE);
        positionLabel.setGravity(Gravity.CENTER);
        rootLayout.addView(positionLabel);

        positionBar = new SeekBar(this);
        positionBar.setMax(1000);
        positionBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    long target = player.getDuration() * progress / 1000L;
                    positionLabel.setText(formatMs(target) + " / " + formatMs(player.getDuration()));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                draggingPosition = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * seekBar.getProgress() / 1000L);
                }
                draggingPosition = false;
                updatePositionUi();
            }
        });
        rootLayout.addView(positionBar);

        rootLayout.addView(row(
                btn("扫描文件夹", v -> pickFolder()),
                btn("新建列表", v -> createPlaylist()),
                btn("列表管理", v -> showPlaylistManager()),
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
        rootLayout.addView(playbackRow);

        rootLayout.addView(row(
                btn("快退15秒", v -> seekBy(-15000)),
                btn("快进30秒", v -> seekBy(30000)),
                btn("随机播放", v -> playRandom()),
                btn("睡眠定时", v -> showSleepTimerDialog())
        ));

        rootLayout.addView(row(
                btn("收藏当前", v -> addCurrentToFavorites()),
                btn("最近播放", v -> switchToPlaylist(PLAYLIST_RECENT)),
                btn("移出列表", v -> removeCurrentFromPlaylist()),
                btn("清搜索", v -> clearSearch())
        ));

        playlistSpinner = new Spinner(this);
        rootLayout.addView(playlistSpinner, fullWrap());
        playlistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedPlaylist = String.valueOf(parent.getItemAtPosition(position));
                prefs.edit().putString("selectedPlaylist", selectedPlaylist).apply();
                List<MediaEntry> items = playlists.get(selectedPlaylist);
                if (items != null) {
                    currentIndex = -1;
                    setQueue(items, false);
                    status("已切换列表：" + selectedPlaylist + "，" + items.size() + " 个文件");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        searchBox = new EditText(this);
        searchBox.setSingleLine(true);
        searchBox.setHint("搜索当前列表");
        searchBox.setTextColor(COLOR_TEXT);
        searchBox.setHintTextColor(COLOR_SUBTLE);
        searchBox.setBackgroundColor(COLOR_PANEL);
        searchBox.setPadding(dp(10), 0, dp(10), 0);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                refreshMediaList();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        rootLayout.addView(searchBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        speedLabel = label("", 14, COLOR_SUBTLE);
        speedBar = new SeekBar(this);
        speedBar.setMax(775);
        speedBar.setProgress(Math.round((prefs.getFloat("lastSpeed", 1f) - 0.25f) * 100f));
        speedBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            float speed = (progress + 25) / 100f;
            setSpeed(speed, true);
        }));
        rootLayout.addView(speedLabel);
        rootLayout.addView(speedBar);

        volumeBar = new SeekBar(this);
        volumeBar.setMax(100);
        volumeBar.setProgress(prefs.getInt("volume", 100));
        volumeBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            if (player != null) player.setVolume(progress / 100f);
            prefs.edit().putInt("volume", progress).apply();
        }));
        rootLayout.addView(label("播放音量", 14, COLOR_SUBTLE));
        rootLayout.addView(volumeBar);

        advancedToggleButton = btn("展开高级控制", v -> toggleAdvancedPanel());
        rootLayout.addView(advancedToggleButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);
        rootLayout.addView(advancedPanel, fullWrap());

        boostBar = new SeekBar(this);
        boostBar.setMax(1500);
        boostBar.setProgress(prefs.getInt("boost", 0));
        boostBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("boost", progress).apply();
            applyAudioEffects();
        }));
        advancedPanel.addView(label("无损增益（不改源文件）", 14, COLOR_SUBTLE));
        advancedPanel.addView(boostBar);

        bassBar = new SeekBar(this);
        bassBar.setMax(1000);
        bassBar.setProgress(prefs.getInt("bass", 0));
        bassBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("bass", progress).apply();
            applyAudioEffects();
        }));
        advancedPanel.addView(label("低音增强", 14, COLOR_SUBTLE));
        advancedPanel.addView(bassBar);

        stereoBar = new SeekBar(this);
        stereoBar.setMax(1000);
        stereoBar.setProgress(prefs.getInt("stereo", 0));
        stereoBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("stereo", progress).apply();
            applyAudioEffects();
        }));
        advancedPanel.addView(label("立体感增强", 14, COLOR_SUBTLE));
        advancedPanel.addView(stereoBar);

        loopLabel = label("", 14, COLOR_SUBTLE);
        rootLayout.addView(loopLabel);
        rootLayout.addView(row(
                btn("循环模式", v -> cycleLoop()),
                btn("A点", v -> setA()),
                btn("B点", v -> setB()),
                btn("清AB", v -> clearAb())
        ));
        advancedPanel.addView(row(
                btn("A-1秒", v -> adjustAbPoint(true, -1000)),
                btn("A+1秒", v -> adjustAbPoint(true, 1000)),
                btn("B-1秒", v -> adjustAbPoint(false, -1000)),
                btn("B+1秒", v -> adjustAbPoint(false, 1000))
        ));
        advancedPanel.addView(row(
                btn("加书签", v -> addBookmark()),
                btn("书签列表", v -> showBookmarks()),
                btn("回到开头", v -> seekToStart()),
                btn("清位置", v -> clearCurrentPosition())
        ));

        rootLayout.addView(row(
                btn("导入TXT", v -> pickText()),
                btn("朗读/暂停", v -> speakText()),
                btn("停止朗读", v -> stopSpeaking()),
                btn("背景音乐", v -> pickBackground())
        ));
        voiceSpinner = new Spinner(this);
        rootLayout.addView(voiceSpinner, fullWrap());
        ttsRateBar = new SeekBar(this);
        ttsRateBar.setMax(150);
        ttsRateBar.setProgress(prefs.getInt("ttsRate", 75));
        ttsRateBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("ttsRate", progress).apply();
            applyTtsSettings();
        }));
        advancedPanel.addView(label("朗读语速", 14, COLOR_SUBTLE));
        advancedPanel.addView(ttsRateBar);

        ttsPitchBar = new SeekBar(this);
        ttsPitchBar.setMax(100);
        ttsPitchBar.setProgress(prefs.getInt("ttsPitch", 50));
        ttsPitchBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            prefs.edit().putInt("ttsPitch", progress).apply();
            applyTtsSettings();
        }));
        advancedPanel.addView(label("朗读音调", 14, COLOR_SUBTLE));
        advancedPanel.addView(ttsPitchBar);

        bgVolumeBar = new SeekBar(this);
        bgVolumeBar.setMax(100);
        bgVolumeBar.setProgress(prefs.getInt("bgVolume", 25));
        bgVolumeBar.setOnSeekBarChangeListener(simpleSeek((bar, progress, fromUser) -> {
            bgPlayer.setVolume(progress / 100f);
            prefs.edit().putInt("bgVolume", progress).apply();
        }));
        advancedPanel.addView(label("朗读背景音量", 14, COLOR_SUBTLE));
        advancedPanel.addView(bgVolumeBar);

        status = label("请选择文件夹开始扫描", 14, COLOR_SUBTLE);
        rootLayout.addView(status);

        mediaList = new ListView(this);
        mediaList.setBackgroundColor(COLOR_PANEL);
        mediaAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        mediaList.setAdapter(mediaAdapter);
        mediaList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleQueueIndexes.size()) return;
            int queueIndex = visibleQueueIndexes.get(position);
            if (queueIndex == currentIndex) {
                togglePlay();
            } else {
                playAt(queueIndex);
            }
        });
        mediaList.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleQueueIndexes.size()) return true;
            showQueueItemActions(visibleQueueIndexes.get(position));
            return true;
        });
        rootLayout.addView(mediaList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(rootLayout);
        setSpeed((speedBar.getProgress() + 25) / 100f, false);
        bgPlayer.setVolume(bgVolumeBar.getProgress() / 100f);
        updateLoopLabel();
        updatePlayPauseButton();
        updatePositionUi();
    }

    private void toggleAdvancedPanel() {
        advancedVisible = !advancedVisible;
        if (advancedPanel != null) advancedPanel.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
        if (advancedToggleButton != null) advancedToggleButton.setText(advancedVisible ? "收起高级控制" : "展开高级控制");
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
        ensureSmartPlaylists();
        playlists.put(PLAYLIST_ALL, new ArrayList<>(library));
        for (Map.Entry<String, List<MediaEntry>> bucket : folderBuckets.entrySet()) {
            Collections.sort(bucket.getValue(), this::compareMedia);
            playlists.put("文件夹：" + bucket.getKey(), new ArrayList<>(bucket.getValue()));
        }
        savePlaylists();
        selectedPlaylist = PLAYLIST_ALL;
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
        if (source == null) source = new ArrayList<>();
        List<MediaEntry> snapshot = new ArrayList<>(source);
        queue.clear();
        queue.addAll(snapshot);
        syncPlayerQueue();
        refreshMediaList();
        if (showLibrary && !queue.isEmpty()) {
            status("可点选文件播放；再次点当前播放项可暂停/继续");
        }
    }

    private void syncPlayerQueue() {
        if (player == null) return;
        List<MediaItem> items = new ArrayList<>();
        for (MediaEntry entry : queue) {
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setArtist(entry.folderName)
                    .build();
            items.add(new MediaItem.Builder()
                    .setUri(Uri.parse(entry.uri))
                    .setMediaMetadata(metadata)
                    .build());
        }
        long position = currentIndex >= 0 ? player.getCurrentPosition() : 0;
        int safeIndex = currentIndex >= 0 && currentIndex < items.size() ? currentIndex : 0;
        player.setMediaItems(items, safeIndex, position);
        player.prepare();
        player.setVolume(volumeBar == null ? 1f : volumeBar.getProgress() / 100f);
    }

    private void refreshMediaList() {
        mediaAdapter.clear();
        visibleQueueIndexes.clear();
        for (int i = 0; i < queue.size(); i++) {
            MediaEntry entry = queue.get(i);
            if (!matchesSearch(entry)) continue;
            visibleQueueIndexes.add(i);
            String prefix = i == currentIndex ? "正在播放  " : "";
            mediaAdapter.add(prefix + (entry.type.equals("video") ? "[视频] " : "[音频] ") + entry.title + "\n" + entry.folderName);
        }
        mediaAdapter.notifyDataSetChanged();
    }

    private boolean matchesSearch(MediaEntry entry) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        return entry.title.toLowerCase(Locale.ROOT).contains(searchQuery)
                || entry.folderName.toLowerCase(Locale.ROOT).contains(searchQuery)
                || entry.type.toLowerCase(Locale.ROOT).contains(searchQuery);
    }

    private void playAt(int index) {
        if (player == null) {
            status("播放器服务正在启动，请稍后再试");
            return;
        }
        if (index < 0 || index >= queue.size()) return;
        saveCurrentPosition();
        currentIndex = index;
        MediaEntry entry = queue.get(index);
        suppressPositionSave = false;
        nowPlaying.setText(entry.title + "\n" + entry.folderName);
        if (player.getMediaItemCount() != queue.size()) syncPlayerQueue();
        player.seekTo(index, 0);
        long last = prefs.getLong("pos:" + entry.uri, 0);
        if (last > 0) player.seekTo(last);
        float folderSpeed = prefs.getFloat(speedKey(entry), prefs.getFloat("lastSpeed", 1f));
        speedBar.setProgress(Math.round((folderSpeed - 0.25f) * 100f));
        setSpeed(folderSpeed, false);
        loadAb(entry.uri);
        updateRecentPlaylist(entry);
        prefs.edit()
                .putString("currentUri", entry.uri)
                .putString("selectedPlaylist", selectedPlaylist)
                .putInt("currentIndex", currentIndex)
                .apply();
        player.play();
        if (playbackService != null) playbackService.refreshNotification();
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
        if (player == null) {
            status("播放器服务正在启动，请稍后再试");
            return;
        }
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

    private void seekBy(long deltaMs) {
        if (player == null) return;
        if (currentIndex < 0) return;
        long duration = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (duration > 0 && duration != C.TIME_UNSET) target = Math.min(duration, target);
        player.seekTo(target);
        updatePositionUi();
        saveCurrentPosition();
    }

    private void playRandom() {
        if (queue.isEmpty()) return;
        if (queue.size() == 1) {
            playAt(0);
            return;
        }
        int next;
        do {
            next = random.nextInt(queue.size());
        } while (next == currentIndex);
        playAt(next);
    }

    private void toggleFullScreenVideo() {
        if (rootLayout == null || playerView == null) return;
        fullScreenVideo = !fullScreenVideo;
        fullscreenHiddenViews.clear();
        for (int i = 0; i < rootLayout.getChildCount(); i++) {
            View child = rootLayout.getChildAt(i);
            if (child != playerView && fullScreenVideo) {
                fullscreenHiddenViews.add(child);
                child.setVisibility(View.GONE);
            } else if (!fullScreenVideo) {
                child.setVisibility(View.VISIBLE);
            }
        }
        ViewGroup.LayoutParams params = playerView.getLayoutParams();
        params.height = fullScreenVideo ? ViewGroup.LayoutParams.MATCH_PARENT : dp(210);
        playerView.setLayoutParams(params);
        rootLayout.setPadding(fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10));
        setRequestedOrientation(fullScreenVideo ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(fullScreenVideo
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
        status(fullScreenVideo ? "已进入视频全屏，点画面或返回退出" : "已退出全屏");
    }

    @Override
    public void onBackPressed() {
        if (fullScreenVideo) {
            toggleFullScreenVideo();
            return;
        }
        super.onBackPressed();
    }

    private void showSleepTimerDialog() {
        String[] options = {"关闭定时", "15 分钟", "30 分钟", "60 分钟", "播完当前停止"};
        new AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setItems(options, (dialog, which) -> {
                    handler.removeCallbacks(sleepTimer);
                    if (which == 0) {
                        prefs.edit().remove("sleepAt").apply();
                        status("已关闭睡眠定时");
                    } else if (which == 4) {
                        prefs.edit().putBoolean("stopAfterCurrent", true).apply();
                        status("当前音频播放结束后停止");
                    } else {
                        int minutes = which == 1 ? 15 : which == 2 ? 30 : 60;
                        long sleepAt = System.currentTimeMillis() + minutes * 60_000L;
                        prefs.edit().putLong("sleepAt", sleepAt).putBoolean("stopAfterCurrent", false).apply();
                        handler.postDelayed(sleepTimer, minutes * 60_000L);
                        status("将在 " + minutes + " 分钟后停止播放");
                    }
                })
                .show();
    }

    private final Runnable sleepTimer = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            player.pause();
            saveCurrentPosition();
            prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).apply();
            updatePlayPauseButton();
            status("睡眠定时已停止播放");
        }
    };

    private void handleEnded() {
        if (player == null) return;
        saveCurrentPositionAsZero();
        if (loopMode == 1) {
            player.seekTo(0);
            player.play();
        } else if (loopMode == 2 && !queue.isEmpty()) {
            playRelative(1);
        } else if (prefs.getBoolean("stopAfterCurrent", false)) {
            prefs.edit().putBoolean("stopAfterCurrent", false).apply();
            player.pause();
            updatePlayPauseButton();
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
        if (player == null || positionBar == null || positionLabel == null || draggingPosition) return;
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
        if (player == null) {
            playPauseButton.setText("播放");
            return;
        }
        playPauseButton.setText(player.isPlaying() ? "暂停" : "播放");
    }

    private void setSpeed(float speed, boolean persist) {
        float fixed = Math.max(0.25f, Math.min(8f, speed));
        speedLabel.setText(String.format(Locale.CHINA, "播放速度 %.2fx", fixed));
        if (player != null) player.setPlaybackParameters(new PlaybackParameters(fixed));
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
        if (player == null) return;
        abA = player.getCurrentPosition();
        abEnabled = abB != C.TIME_UNSET && abB > abA;
        persistAb();
        updateLoopLabel();
        status("A 点：" + formatMs(abA));
    }

    private void setB() {
        if (player == null) return;
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

    private void adjustAbPoint(boolean adjustA, long deltaMs) {
        if (adjustA) {
            if (abA == C.TIME_UNSET) {
                status("请先设置 A 点");
                return;
            }
            abA = Math.max(0, abA + deltaMs);
            if (abB != C.TIME_UNSET && abA >= abB) abA = Math.max(0, abB - 1000);
            status("A 点：" + formatMs(abA));
        } else {
            if (abB == C.TIME_UNSET) {
                status("请先设置 B 点");
                return;
            }
            abB = Math.max(0, abB + deltaMs);
            if (abA != C.TIME_UNSET && abB <= abA) abB = abA + 1000;
            status("B 点：" + formatMs(abB));
        }
        abEnabled = abA != C.TIME_UNSET && abB != C.TIME_UNSET && abB > abA;
        persistAb();
        updateLoopLabel();
    }

    private void addBookmark() {
        MediaEntry entry = currentEntry();
        if (entry == null || player == null) {
            status("当前没有可添加书签的媒体");
            return;
        }
        long position = player.getCurrentPosition();
        List<Bookmark> marks = loadBookmarks(entry.uri);
        for (Bookmark mark : marks) {
            if (Math.abs(mark.positionMs - position) < 1500) {
                status("附近已经有书签：" + formatMs(mark.positionMs));
                return;
            }
        }
        marks.add(new Bookmark(position, formatMs(position)));
        Collections.sort(marks, (a, b) -> Long.compare(a.positionMs, b.positionMs));
        saveBookmarks(entry.uri, marks);
        status("已添加书签：" + formatMs(position));
    }

    private void showBookmarks() {
        MediaEntry entry = currentEntry();
        if (entry == null || player == null) {
            status("当前没有媒体");
            return;
        }
        List<Bookmark> marks = loadBookmarks(entry.uri);
        if (marks.isEmpty()) {
            status("当前媒体还没有书签");
            return;
        }
        String[] labels = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) {
            labels[i] = marks.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle("书签：" + entry.title)
                .setItems(labels, (dialog, which) -> {
                    Bookmark mark = marks.get(which);
                    player.seekTo(mark.positionMs);
                    status("已跳转：" + mark.label);
                })
                .setNegativeButton("删除书签", (dialog, which) -> showDeleteBookmarkDialog(entry.uri, marks))
                .show();
    }

    private void showDeleteBookmarkDialog(String uri, List<Bookmark> marks) {
        String[] labels = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) labels[i] = marks.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("删除哪个书签？")
                .setItems(labels, (dialog, which) -> {
                    Bookmark removed = marks.remove(which);
                    saveBookmarks(uri, marks);
                    status("已删除书签：" + removed.label);
                })
                .show();
    }

    private void seekToStart() {
        if (player == null) return;
        player.seekTo(0);
        saveCurrentPositionAsZero();
        updatePositionUi();
        status("已回到开头");
    }

    private void clearCurrentPosition() {
        MediaEntry entry = currentEntry();
        if (entry == null) {
            status("当前没有媒体");
            return;
        }
        prefs.edit().remove("pos:" + entry.uri).apply();
        if (player != null) player.seekTo(0);
        updatePositionUi();
        status("已清除当前媒体的播放位置");
    }

    private List<Bookmark> loadBookmarks(String uri) {
        List<Bookmark> marks = new ArrayList<>();
        String raw = prefs.getString("bookmarks:" + uri, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                long position = obj.optLong("position", 0);
                String label = obj.optString("label", formatMs(position));
                marks.add(new Bookmark(position, label));
            }
        } catch (JSONException ignored) {
        }
        return marks;
    }

    private void saveBookmarks(String uri, List<Bookmark> marks) {
        JSONArray array = new JSONArray();
        for (Bookmark mark : marks) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("position", mark.positionMs);
                obj.put("label", mark.label);
                array.put(obj);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString("bookmarks:" + uri, array.toString()).apply();
    }

    private void loadAb(String uri) {
        if (player == null) return;
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
            if (player != null && abEnabled && abA != C.TIME_UNSET && abB != C.TIME_UNSET && player.isPlaying() && player.getCurrentPosition() >= abB) {
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

    private void showPlaylistManager() {
        String[] options = {"刷新上次文件夹", "按数字/名称排序当前列表", "倒序当前列表", "把扫描结果加入当前列表", "重命名当前列表", "删除当前列表", "清空最近播放"};
        new AlertDialog.Builder(this)
                .setTitle("列表管理：" + selectedPlaylist)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        refreshLastFolder();
                    } else if (which == 1) {
                        sortCurrentPlaylist(false);
                    } else if (which == 2) {
                        sortCurrentPlaylist(true);
                    } else if (which == 3) {
                        addLibraryToPlaylist();
                    } else if (which == 4) {
                        renameCurrentPlaylist();
                    } else if (which == 5) {
                        deleteCurrentPlaylist();
                    } else {
                        clearRecentPlaylist();
                    }
                })
                .show();
    }

    private void refreshLastFolder() {
        String raw = prefs.getString("lastFolder", "");
        if (raw.isEmpty()) {
            status("还没有上次扫描的文件夹");
            return;
        }
        scanFolder(Uri.parse(raw));
    }

    private void sortCurrentPlaylist(boolean reverse) {
        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if (items == null || items.isEmpty()) {
            status("当前列表为空");
            return;
        }
        if (PLAYLIST_RECENT.equals(selectedPlaylist)) {
            status("最近播放按播放时间排序，不手动排序");
            return;
        }
        Collections.sort(items, this::compareMedia);
        if (reverse) Collections.reverse(items);
        savePlaylists();
        currentIndex = -1;
        setQueue(items, false);
        status(reverse ? "已倒序当前列表" : "已按数字/名称排序当前列表");
    }

    private void renameCurrentPlaylist() {
        if (!canEditPlaylist(selectedPlaylist)) {
            status("内置或自动列表不能重命名");
            return;
        }
        EditText input = new EditText(this);
        input.setText(selectedPlaylist);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名列表")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        status("列表名不能为空");
                        return;
                    }
                    if (playlists.containsKey(newName)) {
                        status("列表名已存在");
                        return;
                    }
                    List<MediaEntry> items = playlists.remove(selectedPlaylist);
                    playlists.put(newName, items == null ? new ArrayList<>() : items);
                    selectedPlaylist = newName;
                    savePlaylists();
                    refreshPlaylistSpinner();
                    status("已重命名为：" + newName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteCurrentPlaylist() {
        if (!canEditPlaylist(selectedPlaylist)) {
            status("内置或自动列表不能删除");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除列表")
                .setMessage("只删除 App 内列表，不删除源文件。确定删除“" + selectedPlaylist + "”？")
                .setPositiveButton("删除", (dialog, which) -> {
                    playlists.remove(selectedPlaylist);
                    selectedPlaylist = PLAYLIST_DEFAULT;
                    savePlaylists();
                    refreshPlaylistSpinner();
                    setQueue(playlists.get(PLAYLIST_DEFAULT), false);
                    status("列表已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearRecentPlaylist() {
        List<MediaEntry> recent = playlists.computeIfAbsent(PLAYLIST_RECENT, key -> new ArrayList<>());
        recent.clear();
        savePlaylists();
        if (PLAYLIST_RECENT.equals(selectedPlaylist)) {
            currentIndex = -1;
            setQueue(recent, false);
        }
        status("已清空最近播放");
    }

    private boolean canEditPlaylist(String name) {
        return !PLAYLIST_DEFAULT.equals(name)
                && !PLAYLIST_ALL.equals(name)
                && !PLAYLIST_FAVORITES.equals(name)
                && !PLAYLIST_RECENT.equals(name)
                && !name.startsWith("文件夹：");
    }

    private boolean canReorderPlaylist(String name) {
        return !PLAYLIST_ALL.equals(name)
                && !PLAYLIST_RECENT.equals(name)
                && !name.startsWith("文件夹：");
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

    private void addCurrentToFavorites() {
        MediaEntry entry = currentEntry();
        if (entry == null) {
            status("当前没有正在播放的文件");
            return;
        }
        addEntryToFavorites(entry);
    }

    private void addEntryToFavorites(MediaEntry entry) {
        List<MediaEntry> favorites = playlists.computeIfAbsent(PLAYLIST_FAVORITES, key -> new ArrayList<>());
        if (containsUri(favorites, entry.uri)) {
            status("已在收藏中");
            return;
        }
        favorites.add(0, entry);
        savePlaylists();
        refreshPlaylistSpinner();
        status("已收藏：" + entry.title);
    }

    private void updateRecentPlaylist(MediaEntry entry) {
        List<MediaEntry> recent = playlists.computeIfAbsent(PLAYLIST_RECENT, key -> new ArrayList<>());
        removeUri(recent, entry.uri);
        recent.add(0, entry);
        while (recent.size() > 100) recent.remove(recent.size() - 1);
        savePlaylists();
    }

    private void removeCurrentFromPlaylist() {
        MediaEntry entry = currentEntry();
        removeEntryFromPlaylist(entry);
    }

    private void removeEntryFromPlaylist(MediaEntry entry) {
        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if (entry == null || items == null || items.isEmpty()) {
            status("当前列表没有可移出的文件");
            return;
        }
        if (PLAYLIST_ALL.equals(selectedPlaylist) || selectedPlaylist.startsWith("文件夹：")) {
            status("自动列表来自扫描结果，不能手动移出；可以新建列表后管理");
            return;
        }
        if (removeUri(items, entry.uri)) {
            savePlaylists();
            currentIndex = -1;
            setQueue(items, false);
            status("已从 " + selectedPlaylist + " 移出：" + entry.title);
        } else {
            status("当前文件不在这个列表中");
        }
    }

    private void showQueueItemActions(int queueIndex) {
        if (queueIndex < 0 || queueIndex >= queue.size()) return;
        MediaEntry entry = queue.get(queueIndex);
        String[] options = {"播放/暂停", "收藏", "移出当前列表", "上移", "下移"};
        new AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (queueIndex == currentIndex) togglePlay(); else playAt(queueIndex);
                    } else if (which == 1) {
                        addEntryToFavorites(entry);
                    } else if (which == 2) {
                        removeEntryFromPlaylist(entry);
                    } else if (which == 3) {
                        moveQueueItem(queueIndex, -1);
                    } else {
                        moveQueueItem(queueIndex, 1);
                    }
                })
                .show();
    }

    private void moveQueueItem(int queueIndex, int delta) {
        if (!canReorderPlaylist(selectedPlaylist)) {
            status("当前列表不支持手动排序");
            return;
        }
        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if (items == null || queueIndex < 0 || queueIndex >= items.size()) return;
        int target = queueIndex + delta;
        if (target < 0 || target >= items.size()) {
            status("已经到头了");
            return;
        }
        Collections.swap(items, queueIndex, target);
        if (currentIndex == queueIndex) currentIndex = target;
        else if (currentIndex == target) currentIndex = queueIndex;
        savePlaylists();
        setQueue(items, false);
        status("已调整顺序");
    }

    private void switchToPlaylist(String name) {
        if (!playlists.containsKey(name)) playlists.put(name, new ArrayList<>());
        selectedPlaylist = name;
        refreshPlaylistSpinner();
        List<MediaEntry> items = playlists.get(name);
        currentIndex = -1;
        setQueue(items == null ? new ArrayList<>() : items, false);
        status("已切换列表：" + name);
    }

    private void clearSearch() {
        searchQuery = "";
        if (searchBox != null) searchBox.setText("");
        refreshMediaList();
    }

    private void loadPlaylists() {
        playlists.clear();
        playlists.put(PLAYLIST_DEFAULT, new ArrayList<>());
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
        ensureSmartPlaylists();
        refreshPlaylistSpinner();
    }

    private void ensureSmartPlaylists() {
        playlists.computeIfAbsent(PLAYLIST_DEFAULT, key -> new ArrayList<>());
        playlists.computeIfAbsent(PLAYLIST_FAVORITES, key -> new ArrayList<>());
        playlists.computeIfAbsent(PLAYLIST_RECENT, key -> new ArrayList<>());
    }

    private void restoreLastSession() {
        if (player == null) return;
        long sleepAt = prefs.getLong("sleepAt", 0);
        if (sleepAt > System.currentTimeMillis()) {
            handler.postDelayed(sleepTimer, sleepAt - System.currentTimeMillis());
            status("已恢复睡眠定时");
        } else if (sleepAt > 0) {
            prefs.edit().remove("sleepAt").apply();
        }

        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if ((items == null || items.isEmpty()) && playlists.containsKey(PLAYLIST_ALL)) {
            selectedPlaylist = PLAYLIST_ALL;
            items = playlists.get(selectedPlaylist);
        }
        if (items == null || items.isEmpty()) return;

        setQueue(items, false);
        String currentUri = prefs.getString("currentUri", "");
        int restoredIndex = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).uri.equals(currentUri)) {
                restoredIndex = i;
                break;
            }
        }
        if (restoredIndex < 0) restoredIndex = Math.max(0, Math.min(prefs.getInt("currentIndex", 0), queue.size() - 1));

        currentIndex = restoredIndex;
        MediaEntry entry = queue.get(currentIndex);
        nowPlaying.setText(entry.title + "\n" + entry.folderName);
        syncPlayerQueue();
        player.seekTo(currentIndex, 0);
        long last = prefs.getLong("pos:" + entry.uri, 0);
        if (last > 0) player.seekTo(last);
        float folderSpeed = prefs.getFloat(speedKey(entry), prefs.getFloat("lastSpeed", 1f));
        speedBar.setProgress(Math.round((folderSpeed - 0.25f) * 100f));
        setSpeed(folderSpeed, false);
        loadAb(entry.uri);
        refreshMediaList();
        updatePositionUi();
        updatePlayPauseButton();
        status("已恢复上次播放：" + entry.title);
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

    private boolean containsUri(List<MediaEntry> entries, String uri) {
        for (MediaEntry entry : entries) {
            if (entry.uri.equals(uri)) return true;
        }
        return false;
    }

    private boolean removeUri(List<MediaEntry> entries, String uri) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).uri.equals(uri)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.CHINA);
            applyTtsSettings();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override public void onDone(String utteranceId) {
                    runOnUiThread(() -> speakNextTextChunk());
                }

                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> {
                        bgPlayer.pause();
                        status("朗读中断");
                    });
                }
            });
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
        applyTtsSettings();
        textChunks.clear();
        textChunks.addAll(splitTextForTts(importedText));
        currentTextChunk = 0;
        speakNextTextChunk();
        if (bgPlayer.getMediaItemCount() > 0) bgPlayer.play();
        status("开始朗读文本，共 " + textChunks.size() + " 段");
    }

    private void speakNextTextChunk() {
        if (tts == null) return;
        if (currentTextChunk >= textChunks.size()) {
            bgPlayer.pause();
            status("文本朗读完成");
            return;
        }
        String chunk = textChunks.get(currentTextChunk);
        currentTextChunk++;
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "dongting_text_" + currentTextChunk);
        status("朗读进度：" + currentTextChunk + "/" + textChunks.size());
    }

    private List<String> splitTextForTts(String text) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            builder.append(ch);
            boolean boundary = ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?';
            if (builder.length() >= 350 && boundary) {
                chunks.add(builder.toString().trim());
                builder.setLength(0);
            } else if (builder.length() >= 700) {
                chunks.add(builder.toString().trim());
                builder.setLength(0);
            }
        }
        if (builder.length() > 0) chunks.add(builder.toString().trim());
        if (chunks.isEmpty()) chunks.add(normalized);
        return chunks;
    }

    private void applyTtsSettings() {
        if (tts == null) return;
        float rate = 0.5f + (prefs.getInt("ttsRate", 75) / 100f);
        float pitch = 0.5f + (prefs.getInt("ttsPitch", 50) / 100f);
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
    }

    private void stopSpeaking() {
        if (tts != null) tts.stop();
        textChunks.clear();
        currentTextChunk = 0;
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
        if (player == null) return;
        try {
            if (loudnessEnhancer != null) loudnessEnhancer.release();
            if (bassBoost != null) bassBoost.release();
            if (virtualizer != null) virtualizer.release();
            loudnessEnhancer = new LoudnessEnhancer(player.getAudioSessionId());
            loudnessEnhancer.setEnabled(true);
            bassBoost = new BassBoost(0, player.getAudioSessionId());
            bassBoost.setEnabled(true);
            virtualizer = new Virtualizer(0, player.getAudioSessionId());
            virtualizer.setEnabled(true);
            applyAudioEffects();
        } catch (RuntimeException ex) {
            status("当前设备不支持部分系统音效");
        }
    }

    private void applyAudioEffects() {
        if (loudnessEnhancer != null) {
            try {
                loudnessEnhancer.setTargetGain(boostBar.getProgress());
            } catch (RuntimeException ignored) {
            }
        }
        if (bassBoost != null) {
            try {
                bassBoost.setStrength((short) bassBar.getProgress());
            } catch (RuntimeException ignored) {
            }
        }
        if (virtualizer != null) {
            try {
                virtualizer.setStrength((short) stereoBar.getProgress());
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
        if (entry != null && !suppressPositionSave && player != null) {
            prefs.edit()
                    .putLong("pos:" + entry.uri, player.getCurrentPosition())
                    .putString("currentUri", entry.uri)
                    .putString("selectedPlaylist", selectedPlaylist)
                    .putInt("currentIndex", currentIndex)
                    .apply();
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
        if (bassBoost != null) bassBoost.release();
        if (virtualizer != null) virtualizer.release();
        if (playbackService != null) playbackService.refreshNotification();
        try {
            unbindService(playbackConnection);
        } catch (IllegalArgumentException ignored) {
        }
        bgPlayer.release();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private interface SeekHandler {
        void onChanged(SeekBar bar, int progress, boolean fromUser);
    }

    private static class Bookmark {
        final long positionMs;
        final String label;

        Bookmark(long positionMs, String label) {
            this.positionMs = positionMs;
            this.label = label;
        }
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
