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
import android.database.Cursor;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Virtualizer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.AspectRatioFrameLayout;
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

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final String PREFS = "dongting_player";
    private static final String PLAYLIST_DEFAULT = "默认列表";
    private static final String PLAYLIST_ALL = "全部文件";
    private static final String PLAYLIST_OPENED = "打开的文件";
    private static final String PLAYLIST_FAVORITES = "收藏";
    private static final String PLAYLIST_RECENT = "最近播放";
    private static final int MODE_SEQUENCE = 0;
    private static final int MODE_REPEAT_ONE = 1;
    private static final int MODE_REPEAT_LIST = 2;
    private static final int MODE_SHUFFLE = 3;
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
    private AudioManager audioManager;
    private GestureDetector videoGestureDetector;

    private LinearLayout rootLayout;
    private LinearLayout advancedPanel;
    private LinearLayout videoControlBar;
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
    private TextView listSummary;
    private Button advancedToggleButton;
    private Button playPauseButton;
    private Button mediaFilterButton;
    private SeekBar positionBar;
    private SeekBar videoControlSeekBar;
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
    private boolean scanning = false;
    private boolean suppressPositionSave = false;
    private boolean ttsPaused = false;
    private int scannedFileCount = 0;
    private int scannedDirCount = 0;
    private float videoTouchStartX = 0f;
    private float videoTouchStartY = 0f;
    private boolean videoGestureHandled = false;
    private boolean draggingVideoControl = false;
    private String selectedPlaylist = PLAYLIST_DEFAULT;
    private String searchQuery = "";
    private String mediaFilter = "all";
    private String importedText = "";
    private String importedTextKey = "";
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
            if (!restoreRunningPlayback()) restoreLastSession();
            processExternalIntent(getIntent());
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

    private final ActivityResultLauncher<Intent> mediaPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                    openMediaUri(uri);
                }
            });

    private final ActivityResultLauncher<Intent> textPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    int flags = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (SecurityException ignored) {
                    }
                    importedText = readText(uri);
                    importedTextKey = uri.toString();
                    currentTextChunk = prefs.getInt("ttsChunk:" + importedTextKey, 0);
                    prefs.edit().putString("lastTextUri", importedTextKey).apply();
                    status("已导入文本：" + importedText.length() + " 字，进度已恢复到第 " + (currentTextChunk + 1) + " 段");
                }
            });

    private final ActivityResultLauncher<Intent> backgroundPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri uri = result.getData().getData();
                    int flags = result.getData().getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
                        getContentResolver().takePersistableUriPermission(uri, flags);
                    } catch (SecurityException ignored) {
                    }
                    startBackgroundMusic(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedPlaylist = prefs.getString("selectedPlaylist", PLAYLIST_DEFAULT);
        loopMode = prefs.getInt("loopMode", 0);
        advancedVisible = prefs.getBoolean("advancedVisible", false);
        mediaFilter = prefs.getString("mediaFilter", "all");
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setupPlayers();
        setupUi();
        restoreImportedText();
        requestNotificationPermission();
        loadPlaylists();
        tts = new TextToSpeech(this, this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        processExternalIntent(intent);
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
                refreshMediaList();
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
                    if (prefs.getBoolean("stopAfterCurrent", false) && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        prefs.edit().putBoolean("stopAfterCurrent", false).apply();
                        player.pause();
                        status("已播完当前文件并停止");
                    }
                    prefs.edit()
                            .putString("currentUri", entry.uri)
                            .putString("selectedPlaylist", selectedPlaylist)
                            .putInt("currentIndex", currentIndex)
                            .putString(playlistMemoryUriKey(selectedPlaylist), entry.uri)
                            .putInt(playlistMemoryIndexKey(selectedPlaylist), currentIndex)
                            .apply();
                    refreshMediaList();
                }
                updateVideoKeepScreenOn();
                updatePositionUi();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status("播放失败：" + error.getErrorCodeName());
                if (queue.size() > 1) {
                    playRelative(1);
                }
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
        nowPlaying.setOnLongClickListener(v -> {
            showNowPlayingPage();
            return true;
        });
        rootLayout.addView(nowPlaying);

        playerView = new PlayerView(this);
        playerView.setPlayer(player);
        playerView.setUseController(false);
        playerView.setControllerAutoShow(false);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setBackgroundColor(0xFF050708);
        applyVideoResizeMode();
        setupVideoGestures();
        playerView.setOnTouchListener((view, event) -> {
            if (currentEntry() == null || !"video".equals(currentEntry().type)) return false;
            if (videoGestureDetector != null) videoGestureDetector.onTouchEvent(event);
            handleVideoTouch(event);
            return true;
        });
        rootLayout.addView(playerView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        videoControlBar = buildVideoControlBar();
        videoControlBar.setVisibility(View.GONE);
        rootLayout.addView(videoControlBar, fullWrap());

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
                btn("打开文件", v -> pickMediaFile()),
                btn("新建列表", v -> createPlaylist()),
                btn("列表管理", v -> showPlaylistManager())
        ));

        LinearLayout playbackRow = row(
                btn("上一首", v -> playRelative(-1)),
                btn("播放", v -> togglePlay()),
                btn("下一首", v -> playRelative(1)),
                btn("设置", v -> showSettingsDialog())
        );
        playPauseButton = (Button) playbackRow.getChildAt(1);
        playPauseButton.setTextSize(18);
        playPauseButton.setTextColor(0xFF101418);
        playPauseButton.setBackgroundColor(COLOR_ACCENT);
        rootLayout.addView(playbackRow);

        rootLayout.addView(row(
                btn("快退15秒", v -> seekBy(-15000)),
                btn("快进30秒", v -> seekBy(30000)),
                btn("播放页", v -> showNowPlayingPage()),
                btn("视频全屏", v -> enterVideoFullScreen())
        ));

        rootLayout.addView(row(
                btn("收藏当前", v -> addCurrentToFavorites()),
                btn("最近播放", v -> switchToPlaylist(PLAYLIST_RECENT)),
                btn("移出列表", v -> removeCurrentFromPlaylist()),
                btn("清搜索", v -> clearSearch())
        ));
        mediaFilterButton = btn(filterButtonText(), v -> cycleMediaFilter());
        rootLayout.addView(row(
                mediaFilterButton,
                btn("排序", v -> sortCurrentPlaylist(false)),
                btn("倒序", v -> sortCurrentPlaylist(true)),
                btn("加入列表", v -> showAddCurrentToPlaylistDialog())
        ));

        playlistSpinner = new Spinner(this);
        rootLayout.addView(playlistSpinner, fullWrap());
        playlistSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newPlaylist = String.valueOf(parent.getItemAtPosition(position));
                if (newPlaylist.equals(selectedPlaylist) && !queue.isEmpty()) return;
                saveCurrentPosition();
                selectedPlaylist = newPlaylist;
                prefs.edit().putString("selectedPlaylist", selectedPlaylist).apply();
                List<MediaEntry> items = playlists.get(selectedPlaylist);
                if (items != null) {
                    restorePlaylistMemory(selectedPlaylist, items);
                    status("已切换列表：" + selectedPlaylist + "，" + items.size() + " 个文件");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        listSummary = label("", 13, COLOR_SUBTLE);
        rootLayout.addView(listSummary);

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
        advancedPanel.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
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
                btn("朗读分段", v -> showTextChunksDialog()),
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
        mediaAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                int queueIndex = position >= 0 && position < visibleQueueIndexes.size() ? visibleQueueIndexes.get(position) : -1;
                boolean active = queueIndex == currentIndex;
                view.setTextColor(active ? COLOR_ACCENT : COLOR_TEXT);
                view.setTextSize(active ? 17 : 15);
                view.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
                view.setPadding(dp(12), dp(8), dp(12), dp(8));
                view.setBackgroundColor(active ? 0xFF22302D : COLOR_PANEL);
                return view;
            }
        };
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
        advancedToggleButton.setText(advancedVisible ? "收起高级控制" : "展开高级控制");
        setSpeed((speedBar.getProgress() + 25) / 100f, false);
        bgPlayer.setVolume(bgVolumeBar.getProgress() / 100f);
        updateLoopLabel();
        updatePlayPauseButton();
        updatePositionUi();
        applyPlaybackMode();
    }

    private LinearLayout buildVideoControlBar() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xDD101418);
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));

        videoControlSeekBar = new SeekBar(this);
        videoControlSeekBar.setMax(1000);
        videoControlSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    positionLabel.setText(formatMs(player.getDuration() * progress / 1000L) + " / " + formatMs(player.getDuration()));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                draggingVideoControl = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * seekBar.getProgress() / 1000L);
                    saveCurrentPosition();
                }
                draggingVideoControl = false;
                showVideoController();
            }
        });
        panel.addView(videoControlSeekBar);
        panel.addView(row(
                btn("播放/暂停", v -> {
                    togglePlay();
                    showVideoController();
                }),
                btn(videoControlStatusText(), v -> showVideoController()),
                btn("快退15秒", v -> {
                    seekBy(-15000);
                    showVideoController();
                }),
                btn("快进30秒", v -> {
                    seekBy(30000);
                    showVideoController();
                }),
                btn("倍速", v -> showSpeedDialog())
        ));
        panel.addView(row(
                btn("循环", v -> {
                    cycleLoop();
                    showVideoController();
                }),
                btn("适应/填充", v -> {
                    boolean fill = !prefs.getBoolean("videoFillScreen", false);
                    prefs.edit().putBoolean("videoFillScreen", fill).apply();
                    applyVideoResizeMode();
                    status(fill ? "视频全屏：填充画面" : "视频全屏：完整适应");
                    showVideoController();
                }),
                btn("A点", v -> {
                    setA();
                    showVideoController();
                }),
                btn("B点", v -> {
                    setB();
                    showVideoController();
                }),
                btn("清AB", v -> {
                    clearAb();
                    showVideoController();
                }),
                btn("退出", v -> {
                    if (fullScreenVideo) toggleFullScreenVideo();
                })
        ));
        return panel;
    }

    private String videoControlStatusText() {
        String speed = player == null ? "1.00x" : String.format(Locale.CHINA, "%.2fx", player.getPlaybackParameters().speed);
        return speed + " · " + playbackModeName();
    }

    private void toggleAdvancedPanel() {
        advancedVisible = !advancedVisible;
        prefs.edit().putBoolean("advancedVisible", advancedVisible).apply();
        if (advancedPanel != null) advancedPanel.setVisibility(advancedVisible ? View.VISIBLE : View.GONE);
        if (advancedToggleButton != null) advancedToggleButton.setText(advancedVisible ? "收起高级控制" : "展开高级控制");
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPicker.launch(intent);
    }

    private void pickMediaFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        mediaPicker.launch(intent);
    }

    private void processExternalIntent(Intent intent) {
        if (intent == null || player == null) return;
        String action = intent.getAction();
        Uri uri = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && uri != null) {
            openMediaUri(uri);
            intent.setAction(null);
            intent.setData(null);
        }
    }

    private void openMediaUri(Uri uri) {
        if (!isUriReadable(uri)) {
            status("文件不可用或授权已失效");
            return;
        }
        String title = displayName(uri);
        MediaEntry entry = new MediaEntry(
                uri.toString(),
                title,
                PLAYLIST_OPENED,
                "opened",
                mediaTypeForUri(uri, title)
        );
        ensureSmartPlaylists();
        List<MediaEntry> opened = playlists.computeIfAbsent(PLAYLIST_OPENED, key -> new ArrayList<>());
        removeUri(opened, entry.uri);
        opened.add(0, entry);
        while (opened.size() > 100) opened.remove(opened.size() - 1);
        savePlaylists();
        selectedPlaylist = PLAYLIST_OPENED;
        refreshPlaylistSpinner();
        setQueue(opened, false);
        playAt(0);
        status("已打开文件：" + title);
    }

    private String displayName(Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.trim().isEmpty()) return name;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        String path = uri.getLastPathSegment();
        if (path == null || path.trim().isEmpty()) return "打开的文件";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private boolean isEntryAvailable(MediaEntry entry) {
        return entry != null && isUriReadable(Uri.parse(entry.uri));
    }

    private boolean isUriReadable(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            return input != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cleanUnavailableFromCurrentPlaylist() {
        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if (items == null || items.isEmpty()) {
            status("当前列表为空");
            return;
        }
        int removed = removeUnavailableEntries(items);
        if (removed > 0) {
            savePlaylists();
            currentIndex = -1;
            setQueue(items, false);
        }
        status("已清理失效文件：" + removed + " 个");
    }

    private void cleanUnavailableFromAllPlaylists() {
        int removed = 0;
        for (List<MediaEntry> items : playlists.values()) {
            removed += removeUnavailableEntries(items);
        }
        savePlaylists();
        List<MediaEntry> current = playlists.get(selectedPlaylist);
        currentIndex = -1;
        setQueue(current == null ? new ArrayList<>() : current, false);
        status("已全库清理失效文件：" + removed + " 个");
    }

    private int removeUnavailableEntries(List<MediaEntry> items) {
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!isEntryAvailable(items.get(i))) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private void scanFolder(Uri folderUri) {
        if (scanning) {
            status("正在扫描，请稍候");
            return;
        }
        DocumentFile root = DocumentFile.fromTreeUri(this, folderUri);
        if (root == null || !root.exists()) {
            status("无法读取该文件夹");
            return;
        }
        scanning = true;
        scannedFileCount = 0;
        scannedDirCount = 0;
        status("开始扫描文件夹...");
        new Thread(() -> {
            try {
                List<MediaEntry> scannedLibrary = new ArrayList<>();
                Map<String, List<MediaEntry>> folderBuckets = new LinkedHashMap<>();
                Map<String, String> folderNames = new HashMap<>();
                scanInto(root, root.getUri().toString(), scannedLibrary, folderBuckets, folderNames);
                Collections.sort(scannedLibrary, this::compareMedia);
                for (Map.Entry<String, List<MediaEntry>> bucket : folderBuckets.entrySet()) {
                    Collections.sort(bucket.getValue(), this::compareMedia);
                }
                runOnUiThread(() -> applyScanResult(folderUri, scannedLibrary, folderBuckets));
            } catch (RuntimeException ex) {
                runOnUiThread(() -> {
                    scanning = false;
                    status("扫描失败：" + ex.getMessage());
                });
            }
        }, "dongting-folder-scan").start();
    }

    private void applyScanResult(Uri folderUri, List<MediaEntry> scannedLibrary, Map<String, List<MediaEntry>> folderBuckets) {
        scanning = false;
        library.clear();
        library.addAll(scannedLibrary);
        ensureSmartPlaylists();
        playlists.put(PLAYLIST_ALL, new ArrayList<>(library));
        for (Map.Entry<String, List<MediaEntry>> bucket : folderBuckets.entrySet()) {
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
        scannedDirCount++;
        if (scannedDirCount % 8 == 0) {
            int dirs = scannedDirCount;
            int files = scannedFileCount;
            runOnUiThread(() -> status("扫描中：已进入 " + dirs + " 个文件夹，找到 " + files + " 个音视频"));
        }
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
                        mediaTypeForFile(file)
                );
                output.add(entry);
                scannedFileCount++;
                if (scannedFileCount % 30 == 0) {
                    int dirs = scannedDirCount;
                    int count = scannedFileCount;
                    runOnUiThread(() -> status("扫描中：已找到 " + count + " 个音视频，遍历 " + dirs + " 个文件夹"));
                }
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
        applyPlaybackMode();
    }

    private void refreshMediaList() {
        mediaAdapter.clear();
        visibleQueueIndexes.clear();
        int audioCount = 0;
        int videoCount = 0;
        for (int i = 0; i < queue.size(); i++) {
            MediaEntry entry = queue.get(i);
            if ("video".equals(entry.type)) videoCount++; else audioCount++;
            if (!matchesSearch(entry)) continue;
            visibleQueueIndexes.add(i);
            String prefix = i == currentIndex ? "正在播放  " : "";
            String state = i == currentIndex && player != null && player.isPlaying() ? " · 播放中" : "";
            mediaAdapter.add(prefix + (entry.type.equals("video") ? "[视频] " : "[音频] ") + entry.title + "\n" + entry.folderName + state);
        }
        mediaAdapter.notifyDataSetChanged();
        if (listSummary != null) {
            String filter = "all".equals(mediaFilter) ? "全部" : "audio".equals(mediaFilter) ? "仅音频" : "仅视频";
            listSummary.setText("当前列表 " + queue.size() + " 个文件（音频 " + audioCount + "，视频 " + videoCount + "） · 筛选：" + filter + " · 显示 " + visibleQueueIndexes.size());
        }
    }

    private boolean matchesSearch(MediaEntry entry) {
        if ("audio".equals(mediaFilter) && !"audio".equals(entry.type)) return false;
        if ("video".equals(mediaFilter) && !"video".equals(entry.type)) return false;
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        return entry.title.toLowerCase(Locale.ROOT).contains(searchQuery)
                || entry.folderName.toLowerCase(Locale.ROOT).contains(searchQuery)
                || entry.type.toLowerCase(Locale.ROOT).contains(searchQuery);
    }

    private void cycleMediaFilter() {
        if ("all".equals(mediaFilter)) {
            mediaFilter = "audio";
        } else if ("audio".equals(mediaFilter)) {
            mediaFilter = "video";
        } else {
            mediaFilter = "all";
        }
        prefs.edit().putString("mediaFilter", mediaFilter).apply();
        if (mediaFilterButton != null) mediaFilterButton.setText(filterButtonText());
        refreshMediaList();
    }

    private String filterButtonText() {
        if ("audio".equals(mediaFilter)) return "仅音频";
        if ("video".equals(mediaFilter)) return "仅视频";
        return "全部媒体";
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
        if (!isEntryAvailable(entry)) {
            status("文件不可用：" + entry.title);
            if (queue.size() > 1) playRelative(1);
            return;
        }
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
        applyPlaybackMode();
        updateRecentPlaylist(entry);
        prefs.edit()
                .putString("currentUri", entry.uri)
                .putString("selectedPlaylist", selectedPlaylist)
                .putInt("currentIndex", currentIndex)
                .putString(playlistMemoryUriKey(selectedPlaylist), entry.uri)
                .putInt(playlistMemoryIndexKey(selectedPlaylist), currentIndex)
                .apply();
        player.play();
        if (playbackService != null) playbackService.refreshNotification();
        refreshMediaList();
        updatePlayPauseButton();
        updateVideoKeepScreenOn();
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
        updateVideoKeepScreenOn();
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
        loopMode = MODE_SHUFFLE;
        prefs.edit().putInt("loopMode", loopMode).apply();
        applyPlaybackMode();
        updateLoopLabel();
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

    private void showNowPlayingPage() {
        MediaEntry entry = currentEntry();
        if (entry == null) {
            status("当前没有正在播放的文件");
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(4));

        TextView title = label(entry.title, 20, COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = label(entry.folderName + " · " + ("video".equals(entry.type) ? "视频" : "音频"), 14, COLOR_SUBTLE);
        TextView cover = label("耳朵在树洞里听见了声音\n" + ("video".equals(entry.type) ? "视频播放" : "音频播放"), 18, 0xFFFFC15A);
        cover.setGravity(Gravity.CENTER);
        cover.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cover.setBackgroundColor(0xFF2B2114);
        cover.setPadding(dp(12), dp(24), dp(12), dp(24));
        TextView abInfo = label("", 14, COLOR_SUBTLE);
        TextView bookmarkInfo = label("", 14, COLOR_SUBTLE);
        TextView progressText = label("00:00 / 00:00", 14, COLOR_SUBTLE);
        progressText.setGravity(Gravity.CENTER);
        SeekBar dialogSeek = new SeekBar(this);
        dialogSeek.setMax(1000);
        final boolean[] dragging = {false};
        dialogSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    long target = player.getDuration() * progress / 1000L;
                    progressText.setText(formatMs(target) + " / " + formatMs(player.getDuration()));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                dragging[0] = true;
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null && player.getDuration() > 0 && player.getDuration() != C.TIME_UNSET) {
                    player.seekTo(player.getDuration() * seekBar.getProgress() / 1000L);
                    saveCurrentPosition();
                }
                dragging[0] = false;
            }
        });

        Button dialogPlay = btn(player != null && player.isPlaying() ? "暂停" : "播放", v -> {
            togglePlay();
            dialogPlayText(v);
        });
        Button speedButton = btn(String.format(Locale.CHINA, "%.2fx", player == null ? 1f : player.getPlaybackParameters().speed), v -> showSpeedDialog());
        panel.addView(title);
        panel.addView(subtitle);
        panel.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
        panel.addView(abInfo);
        panel.addView(bookmarkInfo);
        panel.addView(progressText);
        panel.addView(dialogSeek);
        panel.addView(row(
                btn("上一首", v -> playRelative(-1)),
                btn("快退15秒", v -> seekBy(-15000)),
                dialogPlay,
                btn("快进30秒", v -> seekBy(30000)),
                btn("下一首", v -> playRelative(1))
        ));
        panel.addView(row(
                speedButton,
                btn("循环模式", v -> cycleLoop()),
                btn("A点", v -> setA()),
                btn("B点", v -> setB()),
                btn("书签", v -> showBookmarks())
        ));
        panel.addView(row(
                btn("收藏", v -> addCurrentToFavorites()),
                btn("随机", v -> playRandom()),
                btn("清AB", v -> clearAb()),
                btn("全屏", v -> enterVideoFullScreen()),
                btn("回开头", v -> seekToStart())
        ));

        final Runnable[] updater = new Runnable[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("正在播放")
                .setView(panel)
                .setNegativeButton("关闭", null)
                .create();
        updater[0] = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                MediaEntry current = currentEntry();
                if (current != null) {
                    title.setText(current.title);
                    subtitle.setText(current.folderName + " · " + ("video".equals(current.type) ? "视频" : "音频"));
                    cover.setText("video".equals(current.type) ? "视频画面正在洞听" : "耳朵在树洞里听见了声音");
                    List<Bookmark> marks = loadBookmarks(current.uri);
                    bookmarkInfo.setText("书签：" + marks.size() + " 个");
                }
                String ab = abEnabled && abA != C.TIME_UNSET && abB != C.TIME_UNSET
                        ? "AB 循环：" + formatMs(abA) + " - " + formatMs(abB)
                        : "AB 循环：未开启";
                abInfo.setText(ab);
                if (player != null) {
                    long duration = player.getDuration();
                    long position = player.getCurrentPosition();
                    progressText.setText(formatMs(position) + " / " + formatMs(duration));
                    if (!dragging[0] && duration > 0 && duration != C.TIME_UNSET) {
                        dialogSeek.setProgress((int) Math.max(0, Math.min(1000, position * 1000L / duration)));
                    }
                    dialogPlay.setText(player.isPlaying() ? "暂停" : "播放");
                    speedButton.setText(String.format(Locale.CHINA, "%.2fx", player.getPlaybackParameters().speed));
                }
                handler.postDelayed(this, 500);
            }
        };
        dialog.setOnShowListener(d -> handler.post(updater[0]));
        dialog.setOnDismissListener(d -> handler.removeCallbacks(updater[0]));
        dialog.show();
    }

    private void dialogPlayText(View view) {
        if (view instanceof Button && player != null) {
            ((Button) view).setText(player.isPlaying() ? "暂停" : "播放");
        }
    }

    private void showSpeedDialog() {
        String[] options = {"0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x", "4.0x", "8.0x"};
        new AlertDialog.Builder(this)
                .setTitle("播放速度")
                .setItems(options, (dialog, which) -> {
                    float speed = Float.parseFloat(options[which].replace("x", ""));
                    if (speedBar != null) speedBar.setProgress(Math.round((speed - 0.25f) * 100f));
                    setSpeed(speed, true);
                })
                .show();
    }

    private void setupVideoGestures() {
        videoGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();
                status(player != null && player.isPlaying() ? "视频继续播放" : "视频已暂停");
                return true;
            }
        });
    }

    private void handleVideoTouch(MotionEvent event) {
        if (player == null) return;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            videoTouchStartX = event.getX();
            videoTouchStartY = event.getY();
            videoGestureHandled = false;
        } else if (event.getAction() == MotionEvent.ACTION_UP && !videoGestureHandled) {
            float dx = event.getX() - videoTouchStartX;
            float dy = event.getY() - videoTouchStartY;
            if (Math.abs(dx) < dp(36) && Math.abs(dy) < dp(36)) {
                if (fullScreenVideo) {
                    showVideoController();
                } else {
                    enterVideoFullScreen();
                }
                return;
            }
            if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(80)) {
                seekBy(dx > 0 ? 10000 : -10000);
                status(dx > 0 ? "快进 10 秒" : "快退 10 秒");
            } else if (Math.abs(dy) > dp(80)) {
                if (videoTouchStartX > playerView.getWidth() / 2f) {
                    adjustSystemVolume(dy < 0 ? 1 : -1);
                } else {
                    adjustScreenBrightness(dy < 0 ? 0.08f : -0.08f);
                }
            }
            videoGestureHandled = true;
        }
    }

    private void enterVideoFullScreen() {
        MediaEntry entry = currentEntry();
        if (entry == null || !"video".equals(entry.type)) {
            status("当前不是视频");
            return;
        }
        if (!fullScreenVideo) toggleFullScreenVideo();
    }

    private void adjustSystemVolume(int direction) {
        if (audioManager == null) return;
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
        status(direction > 0 ? "音量调高" : "音量调低");
    }

    private void adjustScreenBrightness(float delta) {
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        float current = attrs.screenBrightness < 0 ? 0.5f : attrs.screenBrightness;
        float next = Math.max(0.05f, Math.min(1f, current + delta));
        attrs.screenBrightness = next;
        getWindow().setAttributes(attrs);
        status(String.format(Locale.CHINA, "亮度 %.0f%%", next * 100f));
    }

    private void toggleFullScreenVideo() {
        if (rootLayout == null || playerView == null) return;
        fullScreenVideo = !fullScreenVideo;
        fullscreenHiddenViews.clear();
        for (int i = 0; i < rootLayout.getChildCount(); i++) {
            View child = rootLayout.getChildAt(i);
            if (child != playerView && child != videoControlBar && fullScreenVideo) {
                fullscreenHiddenViews.add(child);
                child.setVisibility(View.GONE);
            } else if (!fullScreenVideo) {
                child.setVisibility(View.VISIBLE);
            }
        }
        if (videoControlBar != null) videoControlBar.setVisibility(fullScreenVideo ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams params = playerView.getLayoutParams();
        params.height = fullScreenVideo ? ViewGroup.LayoutParams.MATCH_PARENT : dp(210);
        playerView.setLayoutParams(params);
        applyVideoResizeMode();
        rootLayout.setPadding(fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10), fullScreenVideo ? 0 : dp(10));
        setRequestedOrientation(fullScreenVideo ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(fullScreenVideo
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
        playerView.setUseController(fullScreenVideo && prefs.getBoolean("videoFullscreenControls", true));
        if (fullScreenVideo) showVideoController();
        updateVideoKeepScreenOn();
        status(fullScreenVideo ? "已进入视频全屏，点画面显示控制条，返回退出" : "已退出全屏");
    }

    private void updateVideoKeepScreenOn() {
        MediaEntry entry = currentEntry();
        boolean keepOn = prefs.getBoolean("videoKeepScreenOn", true)
                && player != null && player.isPlaying() && entry != null && "video".equals(entry.type);
        if (keepOn || fullScreenVideo) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (playerView != null && !fullScreenVideo) playerView.setUseController(false);
    }

    private void applyVideoResizeMode() {
        if (playerView == null) return;
        boolean fill = prefs.getBoolean("videoFillScreen", false);
        playerView.setResizeMode(fullScreenVideo && fill
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    private void showVideoController() {
        if (playerView == null || !prefs.getBoolean("videoFullscreenControls", true)) return;
        playerView.setUseController(true);
        playerView.showController();
        if (videoControlBar != null && fullScreenVideo) {
            videoControlBar.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideVideoControls);
            handler.postDelayed(hideVideoControls, player != null && player.isPlaying() ? 3500 : 7000);
        }
    }

    private final Runnable hideVideoControls = new Runnable() {
        @Override public void run() {
            if (videoControlBar != null && fullScreenVideo && player != null && player.isPlaying() && !draggingVideoControl) {
                videoControlBar.setVisibility(View.GONE);
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (fullScreenVideo) {
            toggleFullScreenVideo();
            return;
        }
        super.onBackPressed();
    }

    private void showSleepTimerDialog() {
        String[] options = {"关闭定时", "15 分钟", "30 分钟", "60 分钟", "播完当前停止", "播完列表停止"};
        new AlertDialog.Builder(this)
                .setTitle("睡眠定时")
                .setItems(options, (dialog, which) -> {
                    handler.removeCallbacks(sleepTimer);
                    if (which == 0) {
                        prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
                        status("已关闭睡眠定时");
                    } else if (which == 4) {
                        prefs.edit().putBoolean("stopAfterCurrent", true).putBoolean("stopAfterList", false).apply();
                        status("当前音频播放结束后停止");
                    } else if (which == 5) {
                        prefs.edit().putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", true).apply();
                        status("当前列表播放结束后停止");
                    } else {
                        int minutes = which == 1 ? 15 : which == 2 ? 30 : 60;
                        long sleepAt = System.currentTimeMillis() + minutes * 60_000L;
                        prefs.edit().putLong("sleepAt", sleepAt).putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
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
            prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
            updatePlayPauseButton();
            status("睡眠定时已停止播放");
        }
    };

    private void handleEnded() {
        if (player == null) return;
        saveCurrentPositionAsZero();
        if (prefs.getBoolean("stopAfterCurrent", false)) {
            prefs.edit().putBoolean("stopAfterCurrent", false).apply();
            player.pause();
            updatePlayPauseButton();
            status("已播完当前文件并停止");
        } else if (prefs.getBoolean("stopAfterList", false) && currentIndex >= queue.size() - 1) {
            prefs.edit().putBoolean("stopAfterList", false).apply();
            player.pause();
            updatePlayPauseButton();
            status("已播完当前列表并停止");
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
            if (videoControlSeekBar != null && !draggingVideoControl) videoControlSeekBar.setProgress(0);
            positionLabel.setText(formatMs(position) + " / 00:00");
            return;
        }
        int progress = (int) Math.max(0, Math.min(1000, position * 1000L / duration));
        positionBar.setProgress(progress);
        if (videoControlSeekBar != null && !draggingVideoControl) videoControlSeekBar.setProgress(progress);
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
        loopMode = (loopMode + 1) % 4;
        prefs.edit().putInt("loopMode", loopMode).apply();
        applyPlaybackMode();
        updateLoopLabel();
        status(playbackModeName());
    }

    private void updateLoopLabel() {
        String text = "播放模式：" + playbackModeName();
        if (prefs.getBoolean("stopAfterCurrent", false)) text += " | 播完当前停止";
        if (prefs.getBoolean("stopAfterList", false)) text += " | 播完列表停止";
        String ab = abEnabled && abA != C.TIME_UNSET && abB != C.TIME_UNSET ? " | AB：" + formatMs(abA) + "-" + formatMs(abB) : "";
        loopLabel.setText(text + ab);
    }

    private String playbackModeName() {
        if (loopMode == MODE_REPEAT_ONE) return "单曲循环";
        if (loopMode == MODE_REPEAT_LIST) return "列表循环";
        if (loopMode == MODE_SHUFFLE) return "随机循环";
        return "顺序播放";
    }

    private void applyPlaybackMode() {
        if (player == null) return;
        player.setShuffleModeEnabled(loopMode == MODE_SHUFFLE);
        if (loopMode == MODE_REPEAT_ONE) {
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
        } else if (loopMode == MODE_REPEAT_LIST || loopMode == MODE_SHUFFLE) {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
        } else {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        }
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
        String[] options = {"刷新上次文件夹", "按数字/名称排序当前列表", "倒序当前列表", "清理当前列表失效文件", "全库清理失效文件", "批量操作当前列表", "把扫描结果加入当前列表", "重命名当前列表", "删除当前列表", "清空最近播放"};
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
                        cleanUnavailableFromCurrentPlaylist();
                    } else if (which == 4) {
                        cleanUnavailableFromAllPlaylists();
                    } else if (which == 5) {
                        showPlaylistBulkActions();
                    } else if (which == 6) {
                        addLibraryToPlaylist();
                    } else if (which == 7) {
                        renameCurrentPlaylist();
                    } else if (which == 8) {
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

    private void showPlaylistBulkActions() {
        List<MediaEntry> items = playlists.get(selectedPlaylist);
        if (items == null || items.isEmpty()) {
            status("当前列表为空");
            return;
        }
        String[] options = {"全部加入收藏", "移除重复文件", "只保留音频", "只保留视频"};
        new AlertDialog.Builder(this)
                .setTitle("批量操作：" + selectedPlaylist)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        List<MediaEntry> favorites = playlists.computeIfAbsent(PLAYLIST_FAVORITES, key -> new ArrayList<>());
                        int added = 0;
                        for (MediaEntry entry : items) {
                            if (!containsUri(favorites, entry.uri)) {
                                favorites.add(entry);
                                added++;
                            }
                        }
                        savePlaylists();
                        status("已批量收藏：" + added + " 个");
                    } else if (which == 1) {
                        int removed = removeDuplicateEntries(items);
                        savePlaylists();
                        setQueue(items, false);
                        status("已移除重复：" + removed + " 个");
                    } else {
                        String keepType = which == 2 ? "audio" : "video";
                        int removed = removeOtherTypeEntries(items, keepType);
                        savePlaylists();
                        currentIndex = -1;
                        setQueue(items, false);
                        status("已移除不匹配文件：" + removed + " 个");
                    }
                })
                .show();
    }

    private int removeDuplicateEntries(List<MediaEntry> items) {
        Set<String> seen = new java.util.HashSet<>();
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!seen.add(items.get(i).uri)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
    }

    private int removeOtherTypeEntries(List<MediaEntry> items, String keepType) {
        int removed = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (!keepType.equals(items.get(i).type)) {
                items.remove(i);
                removed++;
            }
        }
        return removed;
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
                && !PLAYLIST_OPENED.equals(name)
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

    private void showAddCurrentToPlaylistDialog() {
        MediaEntry entry = currentEntry();
        if (entry == null) {
            status("当前没有正在播放的文件");
            return;
        }
        showAddToPlaylistDialog(entry);
    }

    private void showAddToPlaylistDialog(MediaEntry entry) {
        List<String> names = new ArrayList<>();
        for (String name : playlists.keySet()) {
            if (canEditPlaylist(name) || PLAYLIST_DEFAULT.equals(name)) names.add(name);
        }
        Collections.sort(names, collator);
        if (names.isEmpty()) {
            status("请先新建普通列表");
            return;
        }
        String[] options = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("加入到列表：" + entry.title)
                .setItems(options, (dialog, which) -> {
                    String name = options[which];
                    List<MediaEntry> items = playlists.computeIfAbsent(name, key -> new ArrayList<>());
                    if (containsUri(items, entry.uri)) {
                        status("该文件已在列表中：" + name);
                        return;
                    }
                    items.add(entry);
                    savePlaylists();
                    refreshPlaylistSpinner();
                    status("已加入：" + name);
                })
                .show();
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
        String[] options = {"播放/暂停", "收藏", "加入到列表", "移出当前列表", "上移", "下移"};
        new AlertDialog.Builder(this)
                .setTitle(entry.title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (queueIndex == currentIndex) togglePlay(); else playAt(queueIndex);
                    } else if (which == 1) {
                        addEntryToFavorites(entry);
                    } else if (which == 2) {
                        showAddToPlaylistDialog(entry);
                    } else if (which == 3) {
                        removeEntryFromPlaylist(entry);
                    } else if (which == 4) {
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
        saveCurrentPosition();
        selectedPlaylist = name;
        refreshPlaylistSpinner();
        List<MediaEntry> items = playlists.get(name);
        restorePlaylistMemory(name, items == null ? new ArrayList<>() : items);
        status("已切换列表：" + name);
    }

    private void clearSearch() {
        searchQuery = "";
        if (searchBox != null) searchBox.setText("");
        refreshMediaList();
    }

    private void restorePlaylistMemory(String playlistName, List<MediaEntry> items) {
        if (items == null) items = new ArrayList<>();
        if (items.isEmpty()) {
            currentIndex = -1;
            setQueue(items, false);
            nowPlaying.setText("洞听播放器");
            return;
        }

        String savedUri = prefs.getString(playlistMemoryUriKey(playlistName), "");
        int restoredIndex = indexOfUri(items, savedUri);
        if (restoredIndex < 0) {
            restoredIndex = Math.max(0, Math.min(prefs.getInt(playlistMemoryIndexKey(playlistName), 0), items.size() - 1));
        }

        currentIndex = restoredIndex;
        MediaEntry entry = items.get(currentIndex);
        nowPlaying.setText(entry.title + "\n" + entry.folderName);
        setQueue(items, false);
        long last = prefs.getLong("pos:" + entry.uri, 0);
        if (player != null) {
            player.seekTo(currentIndex, Math.max(0, last));
        }
        float folderSpeed = prefs.getFloat(speedKey(entry), prefs.getFloat("lastSpeed", 1f));
        if (speedBar != null) speedBar.setProgress(Math.round((folderSpeed - 0.25f) * 100f));
        setSpeed(folderSpeed, false);
        loadAb(entry.uri);
        refreshMediaList();
        updatePositionUi();
        updatePlayPauseButton();
    }

    private String playlistMemoryUriKey(String playlistName) {
        return "playlistUri:" + playlistName;
    }

    private String playlistMemoryIndexKey(String playlistName) {
        return "playlistIndex:" + playlistName;
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
        playlists.computeIfAbsent(PLAYLIST_OPENED, key -> new ArrayList<>());
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

        applyPlaybackMode();
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
        applyPlaybackMode();
        refreshMediaList();
        updatePositionUi();
        updatePlayPauseButton();
        status("已恢复上次播放：" + entry.title);
    }

    private boolean restoreRunningPlayback() {
        if (player == null || player.getMediaItemCount() <= 0 || player.getCurrentMediaItem() == null) return false;
        String currentUri = "";
        if (player.getCurrentMediaItem().localConfiguration != null) {
            currentUri = player.getCurrentMediaItem().localConfiguration.uri.toString();
        }
        if (currentUri.isEmpty()) return false;

        List<MediaEntry> items = playlists.get(selectedPlaylist);
        int restoredIndex = indexOfUri(items, currentUri);
        if (restoredIndex < 0) {
            for (Map.Entry<String, List<MediaEntry>> playlist : playlists.entrySet()) {
                int found = indexOfUri(playlist.getValue(), currentUri);
                if (found >= 0) {
                    selectedPlaylist = playlist.getKey();
                    items = playlist.getValue();
                    restoredIndex = found;
                    break;
                }
            }
        }
        if (restoredIndex < 0 || items == null || items.isEmpty()) {
            items = entriesFromPlayerQueue();
            restoredIndex = Math.max(0, Math.min(player.getCurrentMediaItemIndex(), items.size() - 1));
        }
        if (items.isEmpty()) return false;

        queue.clear();
        queue.addAll(new ArrayList<>(items));
        currentIndex = restoredIndex;
        MediaEntry entry = queue.get(currentIndex);
        nowPlaying.setText(entry.title + "\n" + entry.folderName);
        loadAb(entry.uri);
        refreshPlaylistSpinner();
        refreshMediaList();
        updateVideoKeepScreenOn();
        status(player.isPlaying() ? "已回到正在播放：" + entry.title : "已回到播放器：" + entry.title);
        return true;
    }

    private int indexOfUri(@Nullable List<MediaEntry> items, String uri) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uri.equals(uri)) return i;
        }
        return -1;
    }

    private List<MediaEntry> entriesFromPlayerQueue() {
        List<MediaEntry> items = new ArrayList<>();
        if (player == null) return items;
        for (int i = 0; i < player.getMediaItemCount(); i++) {
            MediaItem item = player.getMediaItemAt(i);
            if (item.localConfiguration == null) continue;
            Uri uri = item.localConfiguration.uri;
            String title = item.mediaMetadata.title == null ? displayName(uri) : String.valueOf(item.mediaMetadata.title);
            String folder = item.mediaMetadata.artist == null ? PLAYLIST_OPENED : String.valueOf(item.mediaMetadata.artist);
            items.add(new MediaEntry(uri.toString(), title, folder, "running", mediaTypeForUri(uri, title)));
        }
        return items;
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
                    runOnUiThread(() -> {
                        if (!ttsPaused) speakNextTextChunk();
                    });
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
        String savedVoice = prefs.getString("ttsVoice", "");
        int selected = 0;
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(savedVoice)) {
                selected = i;
                break;
            }
        }
        if (!voices.isEmpty()) voiceSpinner.setSelection(selected);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < voices.size()) {
                    prefs.edit().putString("ttsVoice", voices.get(position).getName()).apply();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
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
        if (!importedTextKey.isEmpty()) {
            Intent service = new Intent(this, TextReaderService.class)
                    .setAction(TextReaderService.ACTION_START)
                    .putExtra(TextReaderService.EXTRA_TEXT_URI, importedTextKey);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            status("已交给后台朗读，可在通知栏暂停/继续/停止");
            return;
        }
        if (tts == null) return;
        if (tts.isSpeaking()) {
            stopSpeaking();
            return;
        }
        int selected = voiceSpinner.getSelectedItemPosition();
        if (selected >= 0 && selected < voices.size()) tts.setVoice(voices.get(selected));
        applyTtsSettings();
        textChunks.clear();
        textChunks.addAll(splitTextForTts(importedText));
        if (!importedTextKey.isEmpty()) {
            currentTextChunk = Math.max(0, Math.min(prefs.getInt("ttsChunk:" + importedTextKey, currentTextChunk), Math.max(0, textChunks.size() - 1)));
        }
        ttsPaused = false;
        speakNextTextChunk();
        if (bgPlayer.getMediaItemCount() > 0) bgPlayer.play();
        status("开始朗读文本，共 " + textChunks.size() + " 段");
    }

    private void speakNextTextChunk() {
        if (tts == null) return;
        if (currentTextChunk >= textChunks.size()) {
            bgPlayer.pause();
            if (!importedTextKey.isEmpty()) prefs.edit().putInt("ttsChunk:" + importedTextKey, 0).apply();
            currentTextChunk = 0;
            status("文本朗读完成");
            return;
        }
        String chunk = textChunks.get(currentTextChunk);
        currentTextChunk++;
        if (!importedTextKey.isEmpty()) prefs.edit().putInt("ttsChunk:" + importedTextKey, currentTextChunk - 1).apply();
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "dongting_text_" + currentTextChunk);
        status("朗读进度：" + currentTextChunk + "/" + textChunks.size());
    }

    private void showTextChunksDialog() {
        if (importedText.trim().isEmpty()) {
            status("请先导入 txt 文本");
            return;
        }
        List<String> chunks = splitTextForTts(importedText);
        String[] labels = new String[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i).replace("\n", " ");
            if (chunk.length() > 36) chunk = chunk.substring(0, 36) + "...";
            labels[i] = "第 " + (i + 1) + " 段  " + chunk;
        }
        new AlertDialog.Builder(this)
                .setTitle("朗读分段")
                .setItems(labels, (dialog, which) -> {
                    currentTextChunk = which;
                    if (!importedTextKey.isEmpty()) prefs.edit().putInt("ttsChunk:" + importedTextKey, which).apply();
                    speakText();
                })
                .setNegativeButton("停止朗读", (dialog, which) -> stopSpeaking())
                .show();
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
        stopService(new Intent(this, TextReaderService.class));
        if (tts != null) tts.stop();
        ttsPaused = true;
        if (!importedTextKey.isEmpty()) prefs.edit().putInt("ttsChunk:" + importedTextKey, Math.max(0, currentTextChunk - 1)).apply();
        bgPlayer.pause();
        status("朗读已暂停，进度已记忆");
    }

    private void resetTextProgress() {
        currentTextChunk = 0;
        if (!importedTextKey.isEmpty()) prefs.edit().putInt("ttsChunk:" + importedTextKey, 0).apply();
        status("朗读进度已回到开头");
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
        prefs.edit().putString("ttsBgUri", uri.toString()).apply();
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

    private void restoreImportedText() {
        String raw = prefs.getString("lastTextUri", "");
        if (raw.isEmpty()) return;
        Uri uri = Uri.parse(raw);
        String restored = readText(uri);
        if (!restored.isEmpty()) {
            importedText = restored;
            importedTextKey = raw;
            currentTextChunk = prefs.getInt("ttsChunk:" + importedTextKey, 0);
            status("已恢复上次导入的文本");
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

    private void showSettingsDialog() {
        String[] options = {"设置中心", "系统声音设置", "恢复音效默认", "恢复朗读默认", "朗读从头开始", "清除当前播放位置", "关闭所有睡眠定时"};
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, SettingsActivity.class));
                    } else if (which == 1) {
                        openAndroidSoundSettings();
                    } else if (which == 2) {
                        resetAudioEffects();
                    } else if (which == 3) {
                        resetTtsSettings();
                    } else if (which == 4) {
                        resetTextProgress();
                    } else if (which == 5) {
                        clearCurrentPosition();
                    } else {
                        handler.removeCallbacks(sleepTimer);
                        prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
                        status("已关闭所有睡眠定时");
                    }
                })
                .show();
    }

    private void resetAudioEffects() {
        prefs.edit().putInt("boost", 0).putInt("bass", 0).putInt("stereo", 0).putInt("volume", 100).apply();
        if (boostBar != null) boostBar.setProgress(0);
        if (bassBar != null) bassBar.setProgress(0);
        if (stereoBar != null) stereoBar.setProgress(0);
        if (volumeBar != null) volumeBar.setProgress(100);
        if (player != null) player.setVolume(1f);
        applyAudioEffects();
        status("音效已恢复默认");
    }

    private void resetTtsSettings() {
        prefs.edit().putInt("ttsRate", 75).putInt("ttsPitch", 50).putInt("bgVolume", 25).remove("ttsVoice").apply();
        if (ttsRateBar != null) ttsRateBar.setProgress(75);
        if (ttsPitchBar != null) ttsPitchBar.setProgress(50);
        if (bgVolumeBar != null) bgVolumeBar.setProgress(25);
        if (voiceSpinner != null) voiceSpinner.setSelection(0);
        applyTtsSettings();
        bgPlayer.setVolume(0.25f);
        status("朗读设置已恢复默认");
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
                    .putString(playlistMemoryUriKey(selectedPlaylist), entry.uri)
                    .putInt(playlistMemoryIndexKey(selectedPlaylist), currentIndex)
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
        return MediaUtils.compareTitle(a.title, b.title, collator);
    }

    private boolean isMedia(String name) {
        return MediaUtils.isMedia(name);
    }

    private boolean isVideo(String name) {
        return MediaUtils.isVideo(name);
    }

    private String mediaTypeForUri(Uri uri, String fallbackName) {
        String mime = getContentResolver().getType(uri);
        if (mime != null) {
            String lower = mime.toLowerCase(Locale.ROOT);
            if (lower.startsWith("video/")) return "video";
            if (lower.startsWith("audio/")) return "audio";
        }
        return isVideo(fallbackName) ? "video" : "audio";
    }

    private String mediaTypeForFile(DocumentFile file) {
        String mime = file.getType();
        if (mime != null) {
            String lower = mime.toLowerCase(Locale.ROOT);
            if (lower.startsWith("video/")) return "video";
            if (lower.startsWith("audio/")) return "audio";
        }
        return isVideo(file.getName()) ? "video" : "audio";
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
        return MediaUtils.formatMs(ms);
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
