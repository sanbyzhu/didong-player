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
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
                btn("关闭睡眠定时", v -> {
                    prefs.edit().remove("sleepAt").putBoolean("stopAfterCurrent", false).putBoolean("stopAfterList", false).apply();
                    toast("已关闭睡眠定时");
                }),
                btn("清除当前播放记忆", v -> {
                    prefs.edit().remove("currentUri").remove("currentIndex").apply();
                    toast("已清除当前播放记忆");
                }));

        addSection(root, "音效设置",
                btn("恢复音效默认", v -> {
                    prefs.edit().putInt("boost", 0).putInt("bass", 0).putInt("stereo", 0).putInt("volume", 100).apply();
                    toast("音效已恢复默认");
                }),
                btn("打开系统声音设置", v -> openSoundSettings()));

        addSection(root, "朗读设置",
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
                label("视频播放时会自动保持屏幕常亮；全屏时显示系统播放控制条。", 14, COLOR_SUBTLE));

        addSection(root, "数据管理",
                btn("清空最近播放", v -> {
                    clearPlaylist("最近播放");
                    toast("已清空最近播放");
                }),
                btn("清空收藏", v -> confirmClearPlaylist("收藏")),
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

    private void clearPlaylist(String name) {
        String raw = prefs.getString("playlists", "{}");
        try {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            root.put(name, new org.json.JSONArray());
            prefs.edit().putString("playlists", root.toString()).apply();
        } catch (org.json.JSONException ignored) {
        }
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
