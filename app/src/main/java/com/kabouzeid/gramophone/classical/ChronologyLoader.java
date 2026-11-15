package com.kabouzeid.gramophone.classical;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Loads assets/chronologies.json (the "Works in Order of Composition" pages) once per process. */
public final class ChronologyLoader {
    private static final String TAG = "ChronologyLoader";
    private static volatile Chronology instance;

    private ChronologyLoader() {
    }

    /** Blocking on first call (parses ~25k works): call off the UI thread. */
    @NonNull
    public static Chronology get(@NonNull Context context) {
        Chronology c = instance;
        if (c != null) return c;
        synchronized (ChronologyLoader.class) {
            if (instance == null) instance = load(context.getApplicationContext());
            return instance;
        }
    }

    @Nullable
    public static Chronology peek() {
        return instance;
    }

    private static Chronology load(Context context) {
        List<Chronology.Composer> composers = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(context.getAssets().open("chronologies.json"), Charset.forName("UTF-8")))) {
            r.beginObject();
            while (r.hasNext()) {
                if ("composers".equals(r.nextName())) {
                    r.beginArray();
                    while (r.hasNext()) composers.add(readComposer(r));
                    r.endArray();
                } else {
                    r.skipValue();
                }
            }
            r.endObject();
        } catch (Exception e) {
            Log.e(TAG, "Can't load chronologies", e);
        }
        return new Chronology(composers);
    }

    private static Chronology.Composer readComposer(JsonReader r) throws Exception {
        String name = "";
        List<String> aliases = new ArrayList<>();
        List<Chronology.Work> works = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            switch (r.nextName()) {
                case "name":
                    name = r.nextString();
                    break;
                case "aliases":
                    r.beginArray();
                    while (r.hasNext()) aliases.add(r.nextString());
                    r.endArray();
                    break;
                case "works":
                    r.beginArray();
                    int i = 0;
                    while (r.hasNext()) works.add(readWork(r, ++i));
                    r.endArray();
                    break;
                default:
                    r.skipValue();
            }
        }
        r.endObject();
        return new Chronology.Composer(name, aliases, works);
    }

    private static Chronology.Work readWork(JsonReader r, int position) throws Exception {
        String cat = "", title = "", date = "", genre = "";
        double year = Double.NaN;
        List<String> alts = null;
        r.beginObject();
        while (r.hasNext()) {
            String key = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.nextNull();
                continue;
            }
            switch (key) {
                case "c": cat = r.nextString(); break;
                case "t": title = r.nextString(); break;
                case "d": date = r.nextString(); break;
                case "g": genre = r.nextString(); break;
                case "y": year = r.nextDouble(); break;
                case "a":
                    alts = new ArrayList<>();
                    r.beginArray();
                    while (r.hasNext()) alts.add(r.nextString());
                    r.endArray();
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        // Works are stored in order of composition; use the position as the order.
        return new Chronology.Work(position, cat, alts, title, date, year, genre);
    }
}
