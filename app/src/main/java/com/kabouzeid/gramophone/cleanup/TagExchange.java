package com.kabouzeid.gramophone.cleanup;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The round trip through Claude:
 * <ol>
 * <li>{@link #exportCsv} writes every scanned song's tags to a CSV file to send to Claude.</li>
 * <li>Claude sends back a fixes file (JSON, format below), which {@link #parseFixes} turns
 *     into albums for the normal review screen.</li>
 * </ol>
 *
 * Fixes file:
 * <pre>
 * {"format": "phonograph-fixes", "version": 1,
 *  "albums": [{"title": "Brandenburg Concertos 1-4", "artist": "Johann Sebastian Bach",
 *              "note": "optional, shown above the changes",
 *              "changes": [{"id": 1234, "path": "/storage/.../01.mp3", "field": "grouping",
 *                           "old": "", "new": "Brandenburg Concerto No. 1 in F, BWV 1046",
 *                           "confidence": "sure" | "check", "why": "optional"}]}]}
 * </pre>
 * Fields: title, artist, album, album_artist, composer, conductor, grouping (work), track,
 * year, genre. (No disc: works are linked by composer, work and movement, not by CD layout.)
 */
public final class TagExchange {

    public static final String[] COLUMNS = {"id", "path", "title", "artist", "album", "album_artist",
            "composer", "conductor", "grouping", "genre", "year", "track", "has_artwork", "duration_s"};

    private TagExchange() {
    }

    public static void exportCsv(@NonNull List<FileTags> files, @NonNull File out) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), Charset.forName("UTF-8")))) {
            w.print('﻿'); // so spreadsheet apps read UTF-8 correctly
            w.println(join(COLUMNS));
            for (FileTags f : files) {
                w.println(join(new String[]{
                        String.valueOf(f.id), f.path, f.title, f.artist, f.album, f.albumArtist,
                        f.composer, f.conductor, f.grouping, f.genre, f.year, f.track,
                        f.hasArtwork ? "1" : "0", String.valueOf(Math.round(f.durationMs / 1000.0))}));
            }
        }
    }

    private static String join(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : values[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    public static final class ParseResult {
        public final List<AlbumFix> albums = new ArrayList<>();
        public int changes;
        public int skippedUnknownFile;
        public int skippedBadField;
    }

    /**
     * @param known files from the last scan (by id), used for current values; may be empty.
     */
    @NonNull
    public static ParseResult parseFixes(@NonNull InputStream in, @NonNull Map<Long, FileTags> known) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        String text = sb.toString().trim();
        if (text.startsWith("﻿")) text = text.substring(1);
        JSONObject root = new JSONObject(text);
        if (!"phonograph-fixes".equals(root.optString("format"))) {
            throw new IllegalArgumentException("This isn't a Phonograph fixes file.");
        }
        Map<String, FileTags> byPath = new HashMap<>();
        for (FileTags f : known.values()) byPath.put(f.path, f);

        ParseResult result = new ParseResult();
        JSONArray albums = root.getJSONArray("albums");
        for (int i = 0; i < albums.length(); i++) {
            JSONObject a = albums.getJSONObject(i);
            JSONArray changes = a.optJSONArray("changes");
            if (changes == null || changes.length() == 0) continue;
            String title = a.optString("title", "");
            AlbumFix fix = new AlbumFix("claude:" + i + ":" + title, title, a.optString("artist", ""), 0);
            fix.note = a.optString("note", null);
            Map<String, Boolean> files = new HashMap<>();
            for (int j = 0; j < changes.length(); j++) {
                JSONObject c = changes.getJSONObject(j);
                Change.Field field = field(c.optString("field"));
                if (field == null) {
                    result.skippedBadField++;
                    continue;
                }
                long id = c.optLong("id", -1);
                String path = c.optString("path", "");
                FileTags file = id >= 0 ? known.get(id) : null;
                if (file == null && !path.isEmpty()) file = byPath.get(path);
                if (file == null) {
                    if (id < 0 || path.isEmpty()) {
                        result.skippedUnknownFile++;
                        continue;
                    }
                    file = new FileTags(id, path, "", "", "", "", "", true);
                }
                Category cat = "check".equalsIgnoreCase(c.optString("confidence")) ? Category.CLAUDE_CHECK : Category.CLAUDE_SURE;
                String old = c.has("old") ? c.optString("old", "") : current(file, field);
                Change change = new Change(file, field, old, c.optString("new", ""), cat);
                change.note = c.optString("why", null);
                fix.changes.add(change);
                files.put(file.path, true);
                result.changes++;
            }
            if (fix.changes.isEmpty()) continue;
            AlbumFix sized = new AlbumFix(fix.key, fix.title, fix.artist, files.size());
            sized.note = fix.note;
            sized.changes.addAll(fix.changes);
            result.albums.add(sized);
        }
        return result;
    }

    private static Change.Field field(String name) {
        switch (name.toLowerCase(Locale.ROOT).replace(' ', '_')) {
            case "title": return Change.Field.TITLE;
            case "artist": return Change.Field.ARTIST;
            case "album": return Change.Field.ALBUM;
            case "album_artist":
            case "albumartist": return Change.Field.ALBUM_ARTIST;
            case "composer": return Change.Field.COMPOSER;
            case "conductor": return Change.Field.CONDUCTOR;
            case "grouping":
            case "work": return Change.Field.GROUPING;
            case "track": return Change.Field.TRACK;
            case "year": return Change.Field.YEAR;
            case "genre": return Change.Field.GENRE;
            default: return null;
        }
    }

    private static String current(FileTags f, Change.Field field) {
        switch (field) {
            case TITLE: return f.title;
            case ARTIST: return f.artist;
            case ALBUM: return f.album;
            case ALBUM_ARTIST: return f.albumArtist;
            case COMPOSER: return f.composer;
            case CONDUCTOR: return f.conductor;
            case GROUPING: return f.grouping;
            case TRACK: return f.track;
            case YEAR: return f.year;
            case GENRE: return f.genre;
            default: return "";
        }
    }
}
