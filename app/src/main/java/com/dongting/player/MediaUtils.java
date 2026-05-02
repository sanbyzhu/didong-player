package com.dongting.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import java.text.Collator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MediaUtils {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private MediaUtils() {
    }

    static int compareTitle(String a, String b, Collator collator) {
        Integer an = firstNumber(a);
        Integer bn = firstNumber(b);
        if (an != null && bn != null && !an.equals(bn)) return Integer.compare(an, bn);
        if (an != null && bn == null) return -1;
        if (an == null && bn != null) return 1;
        return collator.compare(a, b);
    }

    static boolean isMedia(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")
                || lower.endsWith(".opus") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".mov")
                || lower.endsWith(".avi");
    }

    static boolean isVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".3gp") || lower.endsWith(".mov") || lower.endsWith(".avi");
    }

    static String formatMs(long ms) {
        if (ms == C.TIME_UNSET || ms < 0) return "--:--";
        long total = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    @Nullable
    private static Integer firstNumber(String value) {
        Matcher matcher = NUMBER_PATTERN.matcher(value == null ? "" : value);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
