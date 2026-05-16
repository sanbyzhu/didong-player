package com.dongting.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
                || lower.endsWith(".opus") || lower.endsWith(".amr") || lower.endsWith(".wma")
                || lower.endsWith(".aiff") || lower.endsWith(".aif") || lower.endsWith(".mp4") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".mov")
                || lower.endsWith(".avi") || lower.endsWith(".m4v") || lower.endsWith(".ts")
                || lower.endsWith(".m2ts") || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    static boolean isVideo(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".3gp") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".m4v") || lower.endsWith(".ts") || lower.endsWith(".m2ts")
                || lower.endsWith(".flv") || lower.endsWith(".wmv");
    }

    static String formatMs(long ms) {
        if (ms == C.TIME_UNSET || ms < 0) return "--:--";
        long total = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", total / 60, total % 60);
    }

    static String decodeTextBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;
        if (looksLikeUtf16Le(bytes)) return new String(bytes, StandardCharsets.UTF_16LE);
        if (looksLikeUtf16Be(bytes)) return new String(bytes, StandardCharsets.UTF_16BE);
        for (String charset : new String[]{"GB18030", "GBK", "Big5"}) {
            String decoded = decodeStrict(bytes, Charset.forName(charset));
            if (decoded != null) return decoded;
        }
        return new String(bytes, Charset.forName("GB18030"));
    }

    @Nullable
    private static String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    private static boolean looksLikeUtf16Le(byte[] bytes) {
        int pairs = Math.min(bytes.length / 2, 200);
        if (pairs < 4) return false;
        int zeros = 0;
        for (int i = 0; i < pairs; i++) if (bytes[i * 2 + 1] == 0) zeros++;
        return zeros > pairs * 0.6f;
    }

    private static boolean looksLikeUtf16Be(byte[] bytes) {
        int pairs = Math.min(bytes.length / 2, 200);
        if (pairs < 4) return false;
        int zeros = 0;
        for (int i = 0; i < pairs; i++) if (bytes[i * 2] == 0) zeros++;
        return zeros > pairs * 0.6f;
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
