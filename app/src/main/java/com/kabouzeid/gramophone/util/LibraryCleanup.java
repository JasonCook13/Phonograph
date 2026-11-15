package com.kabouzeid.gramophone.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kabouzeid.gramophone.App;
import com.kabouzeid.gramophone.model.Song;

import java.io.File;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Smart library": cleans up how messy tags are displayed and grouped, without touching files.
 *
 * - Albums are grouped by (album title, album artist) instead of MediaStore's album id, falling
 *   back to the song's folder when there's no album artist. This re-joins albums that Android
 *   split because tracks had different artists ("feat.") or slightly different titles.
 * - Artists are grouped by their primary name: "Drake feat. Future", "drake" and "Drake" become one.
 * - Titles that are really filenames ("01 - some_song") are tidied, and missing
 *   artist/album names are taken from the folder layout (Music/Artist/Album/track.mp3).
 */
public final class LibraryCleanup {
    public static final String VARIOUS_ARTISTS = "Various Artists";

    public static final Pattern FEAT = Pattern.compile(
            "\\s*[(\\[]?\\s*\\b(feat\\.?|ft\\.?|featuring)\\s.*$", Pattern.CASE_INSENSITIVE);
    // "1-04 Title", "01 Title", "3. Title", "07_Title" -- but not "99 Problems" or "2 Become 1".
    private static final Pattern LEADING_TRACK_NO = Pattern.compile(
            "^\\s*(?:(?:\\d{1,2}[-.]\\d{1,3}|0\\d{1,2})[\\s._-]+|\\d{1,3}\\s*[-._)]+\\s*)");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    public static final Pattern DISC_FOLDER = Pattern.compile("^(cd|disc|disk)\\s*\\d+$", Pattern.CASE_INSENSITIVE);

    /** Folder names that are containers, never an artist or album. */
    private static final Set<String> GENERIC_FOLDERS = new HashSet<>(Arrays.asList(
            "", "0", "emulated", "storage", "sdcard", "music", "download", "downloads", "audio",
            "media", "songs", "mp3", "my music", "documents", "bluetooth", "whatsapp", "telegram",
            "recordings", "podcasts", "ringtones", "notifications", "alarms"));

    private LibraryCleanup() {
    }

    public static boolean isEnabled() {
        App app = App.getInstance();
        return app != null && PreferenceUtil.getInstance(app).smartLibrary();
    }

    // -------------------------------------------------------------------------------------
    // Per-song cleanup (display values)
    // -------------------------------------------------------------------------------------

    /** Returns a copy of {@code song} with tidier display fields. Ids are unchanged. */
    @NonNull
    public static Song clean(@NonNull Song song) {
        String title = cleanTitle(song.title, song.data);
        String artist = song.artistName;
        String album = song.albumName;
        String albumArtist = song.albumArtistName;

        String parent = folderName(song.data, 1);
        String grandParent = folderName(song.data, 2);
        // ".../Artist/Album/CD1/track.mp3" -> step over the disc folder
        if (parent != null && DISC_FOLDER.matcher(parent).matches()) {
            parent = grandParent;
            grandParent = folderName(song.data, 3);
        }

        if (isUnknown(album) && !isGeneric(parent)) album = parent;
        if (isUnknown(artist) && !isGeneric(grandParent) && !isGeneric(parent)) artist = grandParent;

        album = trimToNull(album);
        artist = trimToNull(artist);
        albumArtist = trimToNull(albumArtist);

        return new Song(song.id, title, song.trackNumber, song.year, song.duration, song.data,
                song.dateModified, song.albumId, album == null ? "" : album, song.artistId,
                artist == null ? "" : artist, albumArtist, song.composer);
    }

    @NonNull
    public static String cleanTitle(@Nullable String title, @Nullable String path) {
        if (title == null) title = "";
        String fileName = fileNameWithoutExtension(path);
        // MediaStore uses the file name when a file has no title tag.
        if (title.trim().isEmpty() || (fileName != null && title.trim().equalsIgnoreCase(fileName.trim()))) {
            String t = fileName == null ? title : fileName;
            t = LEADING_TRACK_NO.matcher(t).replaceFirst("");
            t = t.replace('_', ' ').replaceAll("\\s+", " ").trim();
            return t.isEmpty() ? title : t;
        }
        return title.trim();
    }

    // -------------------------------------------------------------------------------------
    // Grouping keys
    // -------------------------------------------------------------------------------------

    /** Songs with the same key belong to the same album. */
    @NonNull
    public static String albumKey(@NonNull Song song) {
        String album = normalize(song.albumName);
        if (!TextUtils.isEmpty(song.albumArtistName)) {
            return album + "\u0000" + normalize(primaryArtist(song.albumArtistName));
        }
        // No album artist tag: tracks of one album almost always share a folder.
        String folder = parentPath(song.data);
        return album + "\u0000@" + (folder == null ? normalize(primaryArtist(song.artistName)) : folder);
    }

    /** Artists with the same key are the same artist. */
    @NonNull
    public static String artistKey(@Nullable String artistName) {
        return normalize(primaryArtist(artistName));
    }

    /** The name an album should show as its artist. */
    @NonNull
    public static String albumArtist(@NonNull List<Song> songs) {
        if (songs.isEmpty()) return "";
        // Classical: the composer is the album artist.
        String composer = classicalComposer(songs);
        if (composer != null) return composer;
        for (Song s : songs) {
            if (!TextUtils.isEmpty(s.albumArtistName)) return s.albumArtistName;
        }
        // Most common primary artist; if no artist has a clear majority it's a compilation.
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (Song s : songs) {
            String primary = primaryArtist(s.artistName);
            String key = normalize(primary);
            Integer c = counts.get(key);
            counts.put(key, c == null ? 1 : c + 1);
            if (!display.containsKey(key)) display.put(key, primary);
        }
        String bestKey = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (counts.size() > 1 && best * 2 <= songs.size()) return VARIOUS_ARTISTS;
        String name = display.get(bestKey);
        return name == null ? songs.get(0).artistName : name;
    }

    /** The composer if most of these songs are classical (one composer, else "Various Composers"); else null. */
    @Nullable
    private static String classicalComposer(@NonNull List<Song> songs) {
        com.kabouzeid.gramophone.classical.Chronology chronology = com.kabouzeid.gramophone.classical.ChronologyLoader.peek();
        String found = null;
        int classical = 0;
        boolean several = false;
        for (Song s : songs) {
            String c = com.kabouzeid.gramophone.classical.ClassicalParser.composerOf(
                    chronology, s.title, s.artistName, s.albumArtistName, s.composer, s.data);
            if (c == null) continue;
            classical++;
            if (found == null) found = c;
            else if (!found.equals(c)) several = true;
        }
        if (classical * 2 <= songs.size()) return null;
        return several ? com.kabouzeid.gramophone.cleanup.CleanupPlanner.VARIOUS_COMPOSERS : found;
    }

    /** "Drake feat. Future" -> "Drake", "Artist (ft. X)" -> "Artist". */
    @NonNull
    public static String primaryArtist(@Nullable String artist) {
        if (artist == null) return "";
        String p = FEAT.matcher(artist).replaceFirst("").trim();
        return p.isEmpty() ? artist.trim() : p;
    }

    /** Case/accents/punctuation-insensitive comparison key; ignores a leading "The" and "&" vs "and". */
    @NonNull
    public static String normalize(@Nullable String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = DIACRITICS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT).trim();
        n = n.replace("&", " and ");
        if (n.startsWith("the ")) n = n.substring(4);
        n = NON_ALNUM.matcher(n).replaceAll(" ").trim();
        return n;
    }

    // -------------------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------------------

    public static boolean isUnknown(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.equals("<unknown>") || t.equals("unknown") || t.equals("unknown artist")
                || t.equals("unknown album");
    }

    public static boolean isGeneric(@Nullable String folder) {
        return folder == null || GENERIC_FOLDERS.contains(folder.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    @Nullable
    private static String parentPath(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return null;
        File parent = new File(path).getParentFile();
        return parent == null ? null : parent.getPath().toLowerCase(Locale.ROOT);
    }

    /** levels=1 -> containing folder, 2 -> the folder above it, ... */
    @Nullable
    public static String folderName(@Nullable String path, int levels) {
        if (TextUtils.isEmpty(path)) return null;
        File f = new File(path);
        for (int i = 0; i < levels && f != null; i++) f = f.getParentFile();
        return f == null ? null : f.getName();
    }

    @Nullable
    public static String fileNameWithoutExtension(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return null;
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
