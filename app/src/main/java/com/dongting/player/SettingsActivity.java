package com.dongting.player;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS = "dongting_player";
    private static final int COLOR_BG = 0xFF160E09;
    private static final int COLOR_PANEL = 0xFF26170E;
    private static final int COLOR_TEXT = 0xFFFFF4E2;
    private static final int COLOR_SUBTLE = 0xFFD7B98E;
    private static final int COLOR_ACCENT = 0xFFFFB451;

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
                slider("无损增益", "boost", 3000, 0),
                slider("低音增强", "bass", 1000, 0),
                slider("立体感增强", "stereo", 1000, 0),
                label("主界面高级控制中还提供五段均衡器，部分手机或蓝牙设备可能限制系统音效。", 13, COLOR_SUBTLE),
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
                    notifyBackgroundVolumeIfNeeded("bgVolume");
                    toast("朗读设置已恢复默认");
                }),
                btn("清除洞听人声绑定", v -> {
                    prefs.edit().remove("ttsVoice").apply();
                    toast("已清除人声绑定，朗读将使用系统默认 TTS 引擎");
                }),
                btn("使用系统TTS", v -> {
                    prefs.edit().putString("ttsProvider", "system").apply();
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已切换为系统 TTS，现有朗读功能保持不变");
                }),
                btn("使用MultiTTS接口", v -> {
                    prefs.edit()
                            .putString("ttsProvider", "multitts")
                            .putString("externalTtsForwardUrl", prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward"))
                            .apply();
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已切换为 MultiTTS 接口，下次朗读会使用 MultiTTS");
                }),
                btn("设置MultiTTS音色ID", v -> showExternalTtsVoiceDialog()),
                btn("设置MultiTTS地址", v -> showExternalTtsUrlDialog()),
                btn("打开MultiTTS", v -> openMultiTtsApp()),
                btn("测试MultiTTS接口", v -> testExternalTtsEndpoint()),
                slider("MultiTTS音量", "externalTtsVolume", 100, 100),
                btn("停止后台朗读", v -> {
                    stopService(new Intent(this, TextReaderService.class));
                    toast("已停止后台朗读");
                }),
                btn("清除 TXT 记忆", v -> confirmClearTextMemory()));

        addSection(root, "视频设置",
                check("视频播放时保持屏幕常亮", "videoKeepScreenOn", true),
                check("视频全屏播放时自动隐藏控制台", "videoFullscreenControls", true),
                check("视频全屏时填充画面", "videoFillScreen", false),
                label("默认完整显示原比例，适合手机竖屏视频；开启填充画面会铺满屏幕但可能裁掉边缘。视频控制台支持导入 SRT 字幕、同名字幕自动匹配、字幕偏移和旋转画面。", 14, COLOR_SUBTLE));

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

        addSection(root, "开发说明",
                label("洞听播放器由朱振坚个人利用 AI 在业余时间开发，仅供个人学习研究使用，莫要用于商业销售。", 14, COLOR_TEXT),
                label("首发在公众号：小二菜园", 14, COLOR_SUBTLE),
                label("微信和 QQ：254850837", 14, COLOR_SUBTLE),
                label("祝大家学习愉快！", 14, COLOR_ACCENT));

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
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                    notifyBackgroundVolumeIfNeeded(key);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(key, seekBar.getProgress()).apply();
                notifyBackgroundVolumeIfNeeded(key);
            }
        });
        box.addView(value);
        box.addView(bar);
        return box;
    }

    private void notifyBackgroundVolumeIfNeeded(String key) {
        if (!"bgVolume".equals(key) || !TextReaderService.isRunning()) return;
        startService(new Intent(this, TextReaderService.class).setAction(TextReaderService.ACTION_BACKGROUND_UPDATE));
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

    private void showExternalTtsVoiceDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString("externalTtsVoice", ""));
        input.setHint("例如 MultiTTS voices 接口里的 voice id，可留空");
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_SUBTLE);
        input.setBackgroundColor(COLOR_PANEL);
        new AlertDialog.Builder(this)
                .setTitle("MultiTTS 音色 ID")
                .setMessage("如果 MultiTTS 的 /voices 能看到 voiceCode 或 voice id，可填在这里。留空则使用 MultiTTS 当前默认音色。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    SharedPreferences.Editor editor = prefs.edit();
                    if (value.isEmpty()) editor.remove("externalTtsVoice"); else editor.putString("externalTtsVoice", value);
                    editor.apply();
                    toast(value.isEmpty() ? "已使用 MultiTTS 默认音色" : "已保存 MultiTTS 音色：" + value);
                })
                .setNeutralButton("清空", (dialog, which) -> {
                    prefs.edit().remove("externalTtsVoice").apply();
                    toast("已清空 MultiTTS 音色 ID");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showExternalTtsUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward"));
        input.setHint("http://127.0.0.1:8774/forward");
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_SUBTLE);
        input.setBackgroundColor(COLOR_PANEL);
        new AlertDialog.Builder(this)
                .setTitle("MultiTTS 转发地址")
                .setMessage("如果 MultiTTS 实际端口不是 8774，可在这里改。必须包含 /forward。")
                .setView(input)
                .setPositiveButton("保存", (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (value.isEmpty()) value = "http://127.0.0.1:8774/forward";
                    prefs.edit().putString("externalTtsForwardUrl", value).apply();
                    toast("已保存 MultiTTS 地址");
                })
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    prefs.edit().putString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward").apply();
                    toast("已恢复默认地址");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openMultiTtsApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("org.nobody.multitts");
        if (intent == null) {
            toast("未找到 MultiTTS：org.nobody.multitts");
            return;
        }
        startActivity(intent);
    }

    private void testExternalTtsEndpoint() {
        toast("正在测试 MultiTTS 接口");
        new Thread(() -> {
            String result = runExternalTtsTest();
            new Handler(Looper.getMainLooper()).post(() -> new AlertDialog.Builder(this)
                    .setTitle("MultiTTS 接口测试")
                    .setMessage(result)
                    .setPositiveButton("确定", null)
                    .show());
        }).start();
    }

    private String runExternalTtsTest() {
        String forward = prefs.getString("externalTtsForwardUrl", "http://127.0.0.1:8774/forward");
        if (forward == null || forward.trim().isEmpty()) forward = "http://127.0.0.1:8774/forward";
        String voices = forward.replace("/forward", "/voices");
        StringBuilder message = new StringBuilder();
        message.append("提供方：").append(prefs.getString("ttsProvider", "system")).append('\n');
        message.append("forward：").append(forward).append('\n');
        message.append("voice：").append(prefs.getString("externalTtsVoice", "默认")).append('\n');
        message.append("上次状态：").append(prefs.getString("lastExternalTtsState", "无")).append('\n');
        message.append("上次错误：").append(prefs.getString("lastExternalTtsError", "无")).append('\n');
        message.append("上次信息：").append(prefs.getString("lastExternalTtsInfo", "无")).append("\n\n");
        message.append("voices：").append(testHttp(voices, false)).append("\n\n");
        try {
            String separator = forward.contains("?") ? "&" : "?";
            String url = forward + separator
                    + "text=" + URLEncoder.encode("洞听 MultiTTS 接口测试。", "UTF-8")
                    + "&speed=50&volume=100&pitch=50";
            String voice = prefs.getString("externalTtsVoice", "");
            if (!voice.trim().isEmpty()) url += "&voice=" + URLEncoder.encode(voice.trim(), "UTF-8");
            message.append("forward测试：").append(testHttp(url, true));
        } catch (Exception ex) {
            message.append("forward测试异常：").append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
        }
        return message.toString();
    }

    private String testHttp(String rawUrl, boolean binary) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            String type = connection.getContentType();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            int total = 0;
            StringBuilder preview = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read;
            while (input != null && (read = input.read(buffer)) != -1 && total < 8192) {
                if (!binary && preview.length() < 600) preview.append(new String(buffer, 0, read));
                total += read;
            }
            return "HTTP " + code + "，类型 " + type + "，读取 " + total + " 字节"
                    + (preview.length() > 0 ? "\n" + preview : "");
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
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
                    notifyBackgroundVolumeIfNeeded("bgVolume");
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
