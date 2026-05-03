package com.dongting.player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.LinkedHashMap;
import java.util.Map;

class DongtingDatabase extends SQLiteOpenHelper {
    private static final String NAME = "dongting.db";
    private static final int VERSION = 1;

    DongtingDatabase(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    void put(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR REPLACE INTO kv(k,v,updated_at) VALUES(?,?,?)",
                new Object[]{key, value == null ? "" : value, System.currentTimeMillis()});
    }

    String get(String key, String fallback) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT v FROM kv WHERE k=?", new String[]{key})) {
            if (cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {
        }
        return fallback;
    }

    void remove(String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("DELETE FROM kv WHERE k=?", new Object[]{key});
    }

    Map<String, String> all() {
        Map<String, String> values = new LinkedHashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT k,v FROM kv ORDER BY k", null)) {
            while (cursor.moveToNext()) values.put(cursor.getString(0), cursor.getString(1));
        } catch (RuntimeException ignored) {
        }
        return values;
    }

    int removePrefix(String prefix) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete("kv", "k LIKE ?", new String[]{prefix + "%"});
    }
}
