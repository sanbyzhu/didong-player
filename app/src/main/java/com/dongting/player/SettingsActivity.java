package com.dongting.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS = "dongting_player";
    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_PANEL = 0xFF1A2027;
    private static final int COLOR_TEXT = 0xFFF3F6F8;
    private static final int COLOR_SUBTLE = 0xFFAAB4BF;
    private static final int COLOR_ACCENT = 0xFF35C2A6;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(root);

        TextView title = label("洞听设置中心", 22, COLOR_TEXT);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);
        root.addView(label("把播放、朗读、视频和数据设置集中到这里。部分播放中的实时设置仍在主界面立即生效。", 13, COLOR_SUBTLE));

        addSection(root, "播放设置",
                btn("恢复全部默认", v -> confirmResetAllDefaults()),
                btn("关闭睡眠定时", v -> {
                    prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
                    toast("已关闭睡眠定时");
                }),
                btn("清除当前播放记忆", v -> {
                    prefs.edit().remove("currentUri").remove("currentIndex").apply();
                    toast("已清除当前播放记忆");
                }));

        addSection(root, "音效设置",
                slider("播放音量", "volume", 100, 100),
                slider("无损增益", "boost", 1500, 0),
                slider("低音增强", "bass", 1000, 0),
                slider("立体感增强", "stereo", 1000, 0),
                btn("恢复音效默认", v -> {
                    prefs.edit().putInt("boost", 0).putInt("bass", 0).putInt("stereo", 0).putInt("volume", 100).apply();
                    toast("音效已恢复默认");
                }),
                btn("打开系统声音设置", v -> openSoundSettings()));

        addSection(root, "朗读设置",
                slider("朗读语速", "ttsRate", 150, 75),
                slider("朗读音调", "ttsPitch", 100, 50),
                slider("背景音乐音量", "bgVolume", 100, 25),
                btn("恢复朗读默认", v -> {
                    prefs.edit().putInt("ttsRate", 75).putInt("ttsPitch", 50).putInt("bgVolume", 25).remove("ttsVoice").apply();
                    toast("朗读设置已恢复默认");
                }),
                btn("停止后台朗读", v -> {
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已停止后台朗读");
                }),
                btn("清除 TXT 记忆", v -> confirmClearTextMemory()));

        addSection(root, "视频设置",
                check("视频播放时保持屏幕常亮", "videoKeepScreenOn", true),
                check("视频全屏时显示系统控制条", "videoFullscreenControls", true),
                check("视频全屏时填充画面", "videoFillScreen", false),
                label("默认完整显示原比例，适合手机竖屏视频；开启填充画面会铺满屏幕但可能裁掉边缘。", 14, COLOR_SUBTLE));

        addSection(root, "数据管理",
                btn("清空最近播放", v -> {
                    clearPlaylist("最近播放");
                    toast("已清空最近播放");
                }),
                btn("清空收藏", v -> confirmClearPlaylist("收藏")),
                btn("清除所有播放位置", v -> confirmClearPrefix("pos:", "播放位置")),
                btn("清除所有 AB 循环", v -> confirmClearPrefix("ab:", "AB 循环")),
                btn("清除所有书签", v -> confirmClearPrefix("bookmarks:", "书签")),
                label("清空列表只会删除 App 内记录，不会删除手机里的音频/视频文件。", 13, COLOR_SUBTLE));

        root.addView(btn("返回播放器", v -> finish()), fullParams());
        return scroll;
    }

    private void addSection(LinearLayout root, String title, View... views) {
        TextView header = label(title, 17, COLOR_ACCENT);
        header.setPadding(0, dp(18), 0, dp(6));
        root.addView(header);
        for (View view : views) root.addView(view, fullParams());
    }

    private View slider(String title, String key, int max, int defaultValue) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(8));
        TextView value = label(title + "：" + prefs.getInt(key, defaultValue), 14, COLOR_SUBTLE);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(prefs.getInt(key, defaultValue));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(title + "：" + progress);
                if (fromUser) prefs.edit().putInt(key, progress).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(key, seekBar.getProgress()).apply();
            }
        });
        box.addView(value);
        box.addView(bar);
        return box;
    }

    private View check(String text, String key, boolean defaultValue) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(COLOR_TEXT);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        box.setChecked(prefs.getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.edit().putBoolean(key, isChecked).apply());
        return box;
    }

    private void confirmClearTextMemory() {
        new AlertDialog.Builder(this)
                .setTitle("清除 TXT 记忆")
                .setMessage("会清除上次导入文本和朗读背景音乐记录，已导入文件本身不会被删除。")
                .setPositiveButton("清除", (dialog, which) -> {
                    prefs.edit().remove("lastTextUri").remove("ttsBgUri").apply();
                    toast("TXT 记忆已清除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmResetAllDefaults() {
        new AlertDialog.Builder(this)
                .setTitle("恢复全部默认")
                .setMessage("会恢复播放、音效、朗读和视频设置，不会删除列表和源文件。")
                .setPositiveButton("恢复", (dialog, which) -> {
                    prefs.edit()
                            .putInt("volume", 100)
                            .putInt("boost", 0)
                            .putInt("bass", 0)
                            .putInt("stereo", 0)
                            .putInt("ttsRate", 75)
                            .putInt("ttsPitch", 50)
                            .putInt("bgVolume", 25)
                            .putBoolean("videoKeepScreenOn", true)
                            .putBoolean("videoFullscreenControls", true)
                            .putBoolean("videoFillScreen", false)
                            .putFloat("lastSpeed", 1f)
                            .remove("ttsVoice")
                            .remove("sleepAt")
                            .putBoolean("stopAfterCurrent", false)
                            .putBoolean("stopAfterList", false)
                            .apply();
                    toast("已恢复全部默认，返回播放器后生效");
                    recreate();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearPlaylist(String name) {
        new AlertDialog.Builder(this)
                .setTitle("清空" + name)
                .setMessage("只清空 App 内列表，不删除源文件。")
                .setPositiveButton("清空", (dialog, which) -> {
                    clearPlaylist(name);
                    toast("已清空" + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearPrefix(String prefix, String label) {
        new AlertDialog.Builder(this)
                .setTitle("清除所有" + label)
                .setMessage("只清除 App 内记忆，不会删除源文件。")
                .setPositiveButton("清除", (dialog, which) -> {
                    int count = clearKeysWithPrefix(prefix);
                    toast("已清除 " + count + " 条" + label + "记录");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int clearKeysWithPrefix(String prefix) {
        return PlaylistStore.clearKeysWithPrefix(prefs, prefix);
    }

    private void clearPlaylist(String name) {
        PlaylistStore.clearPlaylist(prefs, name);
    }

    private void openSoundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        } catch (Exception ignored) {
        }
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

    private LinearLayout.LayoutParams fullParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
