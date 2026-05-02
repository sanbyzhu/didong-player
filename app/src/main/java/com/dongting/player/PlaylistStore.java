package com.dongting.player;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

final class PlaylistStore {
    private PlaylistStore() {
    }

    static int clearKeysWithPrefix(SharedPreferences prefs, String prefix) {
        SharedPreferences.Editor editor = prefs.edit();
        int count = 0;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                editor.remove(entry.getKey());
                count++;
            }
        }
        editor.apply();
        return count;
    }

    static void clearPlaylist(SharedPreferences prefs, String name) {
        String raw = prefs.getString("playlists", "{}");
        try {
            JSONObject root = new JSONObject(raw);
            root.put(name, new org.json.JSONArray());
            prefs.edit().putString("playlists", root.toString()).apply();
        } catch (JSONException ignored) {
        }
    }
}
