package com.kabouzeid.gramophone.cleanup;

import com.kabouzeid.gramophone.util.LibraryCleanup;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which tag changes would make the library consistent. Pure logic: it only looks at
 * the {@link FileTags} it is given and never touches files, so re-planning after the user
 * switches a category on or off is instant.
 */
public final class CleanupPlanner {

    /** Finds a cover image (cover.jpg, folder.png, ...) in a folder, or null. */
    public interface FolderCoverLookup {
        String findCover(String folderPath);
    }

    private static final Pattern FEAT_SPLIT = Pattern.compile(
            "^(.*?)\\s*[(\\[]?\\s*\\b(?:feat\\.?|ft\\.?|featuring)\\s+(.+?)\\s*[)\\]]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_FEAT = Pattern.compile("\\b(feat\\.?|ft\\.|featuring)\\s", Pattern.CASE_INSENSITIVE);
    // "1-04 x", "04 x", "4. x", "4 - x" -- but not "99 Problems".
    private static final Pattern FILE_TRACK_NO = Pattern.compile(
            "^\\s*(?:\\d{1,2}[-.](\\d{1,3})(?=[\\s._-])|(0\\d{1,2})(?=[\\s._-])|(\\d{1,3})(?=\\s*[-._)]))");

    private CleanupPlanner() {
    }

    /** Mutable working copy of one file's tags while the rules run. */
    private static final class Work {
        final FileTags orig;
        String title, artist, album, albumArtist, track;
        final List<Change> changes = new ArrayList<>();

        Work(FileTags f) {
            orig = f;
            title = f.title;
            artist = f.artist;
            album = f.album;
            albumArtist = f.albumArtist;
            track = f.track;
        }

        void set(Change.Field field, String value, Category cat) {
            String old;
            switch (field) {
                case TITLE: old = title; title = value; break;
                case ARTIST: old = artist; artist = value; break;
                case ALBUM: old = album; album = value; break;
                case ALBUM_ARTIST: old = albumArtist; albumArtist = value; break;
                case TRACK: old = track; track = value; break;
                default: old = "";
            }
            if (value.equals(old)) return;
            // Collapse repeated changes to the same field into one (old = the file's original value).
            for (int i = 0; i < changes.size(); i++) {
                Change c = changes.get(i);
                if (c.field == field) {
                    changes.remove(i);
                    if (!value.equals(c.oldValue)) changes.add(new Change(orig, field, c.oldValue, value, cat));
                    return;
                }
            }
            changes.add(new Change(orig, field, old, value, cat));
        }
    }

    public static List<AlbumFix> plan(List<FileTags> files, Set<Category> enabled, FolderCoverLookup covers) {
        List<Work> works = new ArrayList<>();
        for (FileTags f : files) works.add(new Work(f));

        // 1. Missing artist / album from Artist/Album/track folders.
        if (enabled.contains(Category.MISSING_INFO)) {
            for (Work w : works) {
                String parent = LibraryCleanup.folderName(w.orig.path, 1);
                String grand = LibraryCleanup.folderName(w.orig.path, 2);
                if (parent != null && LibraryCleanup.DISC_FOLDER.matcher(parent).matches()) {
                    parent = grand;
                    grand = LibraryCleanup.folderName(w.orig.path, 3);
                }
                if (LibraryCleanup.isUnknown(w.album) && !LibraryCleanup.isGeneric(parent)) {
                    w.set(Change.Field.ALBUM, parent, Category.MISSING_INFO);
                }
                if (LibraryCleanup.isUnknown(w.artist) && !LibraryCleanup.isGeneric(parent) && !LibraryCleanup.isGeneric(grand)) {
                    w.set(Change.Field.ARTIST, grand, Category.MISSING_INFO);
                }
            }
        }

        // 2. Titles that are missing or just the file name.
        if (enabled.contains(Category.TITLES)) {
            for (Work w : works) {
                String fileName = LibraryCleanup.fileNameWithoutExtension(w.orig.path);
                if (w.title.isEmpty() || (fileName != null && w.title.equalsIgnoreCase(fileName.trim()))) {
                    String cleaned = LibraryCleanup.cleanTitle("", w.orig.path);
                    if (!cleaned.isEmpty()) w.set(Change.Field.TITLE, cleaned, Category.TITLES);
                }
            }
        }

        // 3. "Artist feat. X" -> artist "Artist", title "Song (feat. X)".
        if (enabled.contains(Category.FEATURED)) {
            for (Work w : works) {
                Matcher m = FEAT_SPLIT.matcher(w.artist);
                if (!m.matches()) continue;
                String main = m.group(1).trim();
                String featured = m.group(2).trim();
                if (main.isEmpty() || featured.isEmpty()) continue;
                w.set(Change.Field.ARTIST, main, Category.FEATURED);
                if (!w.title.isEmpty() && !HAS_FEAT.matcher(w.title).find()) {
                    w.set(Change.Field.TITLE, w.title + " (feat. " + featured + ")", Category.FEATURED);
                }
            }
        }

        // 4. One spelling per artist across the whole library.
        if (enabled.contains(Category.SPELLING)) {
            Map<String, Map<String, Integer>> spellings = new HashMap<>();
            for (Work w : works) {
                count(spellings, w.artist);
                count(spellings, w.albumArtist);
            }
            Map<String, String> canonical = pickCanonical(spellings);
            for (Work w : works) {
                String a = canonical.get(LibraryCleanup.normalize(w.artist));
                if (a != null && !w.artist.isEmpty()) w.set(Change.Field.ARTIST, a, Category.SPELLING);
                String aa = canonical.get(LibraryCleanup.normalize(w.albumArtist));
                if (aa != null && !w.albumArtist.isEmpty()) w.set(Change.Field.ALBUM_ARTIST, aa, Category.SPELLING);
            }
        }

        // 5. Per album (same album name in the same folder).
        Map<String, List<Work>> groups = new LinkedHashMap<>();
        for (Work w : works) {
            String key = LibraryCleanup.normalize(w.album) + "\u0000" + parentPath(w.orig.path);
            List<Work> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(w);
        }

        List<AlbumFix> result = new ArrayList<>();
        for (Map.Entry<String, List<Work>> e : groups.entrySet()) {
            List<Work> g = e.getValue();
            boolean realAlbum = !LibraryCleanup.isUnknown(g.get(0).album);

            if (realAlbum && enabled.contains(Category.SPELLING)) {
                Map<String, Integer> counts = new HashMap<>();
                for (Work w : g) inc(counts, w.album);
                String best = best(counts);
                for (Work w : g) w.set(Change.Field.ALBUM, best, Category.SPELLING);
            }

            if (realAlbum && enabled.contains(Category.ALBUM_ARTIST)) {
                String albumArtist = albumArtistFor(g);
                if (!albumArtist.isEmpty()) {
                    for (Work w : g) w.set(Change.Field.ALBUM_ARTIST, albumArtist, Category.ALBUM_ARTIST);
                }
            }

            if (enabled.contains(Category.TRACK_NUMBERS)) {
                for (Work w : g) {
                    if (!w.track.isEmpty() && !w.track.equals("0")) continue;
                    String n = trackFromFileName(w.orig.path);
                    if (n != null) w.set(Change.Field.TRACK, n, Category.TRACK_NUMBERS);
                }
            }

            List<Change> artChanges = new ArrayList<>();
            if (realAlbum && enabled.contains(Category.ARTWORK)) {
                String source = null;
                for (Work w : g) {
                    if (w.orig.hasArtwork) {
                        source = w.orig.path;
                        break;
                    }
                }
                if (source == null && covers != null) source = covers.findCover(parentPath(w0(g).orig.path));
                if (source != null) {
                    for (Work w : g) {
                        if (!w.orig.hasArtwork) {
                            artChanges.add(new Change(w.orig, Change.Field.ARTWORK, "", source, Category.ARTWORK));
                        }
                    }
                }
            }

            List<Change> all = new ArrayList<>();
            for (Work w : g) all.addAll(w.changes);
            all.addAll(artChanges);
            if (all.isEmpty()) continue;

            Work first = w0(g);
            String title = first.album.isEmpty() ? nonNull(LibraryCleanup.folderName(first.orig.path, 1)) : first.album;
            String artist = !first.albumArtist.isEmpty() ? first.albumArtist : first.artist;
            AlbumFix fix = new AlbumFix(e.getKey(), title, artist, g.size());
            fix.changes.addAll(all);
            result.add(fix);
        }

        Collections.sort(result, (a, b) -> {
            int c = LibraryCleanup.normalize(a.artist).compareTo(LibraryCleanup.normalize(b.artist));
            return c != 0 ? c : LibraryCleanup.normalize(a.title).compareTo(LibraryCleanup.normalize(b.title));
        });
        return result;
    }

    /** Most common existing album artist; otherwise the majority artist, or "Various Artists". */
    private static String albumArtistFor(List<Work> g) {
        Map<String, Integer> tagged = new HashMap<>();
        for (Work w : g) if (!w.albumArtist.isEmpty()) inc(tagged, w.albumArtist);
        if (!tagged.isEmpty()) return best(tagged);

        Map<String, Integer> byKey = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        for (Work w : g) {
            if (LibraryCleanup.isUnknown(w.artist)) continue;
            String primary = LibraryCleanup.primaryArtist(w.artist);
            String k = LibraryCleanup.normalize(primary);
            inc(byKey, k);
            if (!display.containsKey(k)) display.put(k, primary);
        }
        if (byKey.isEmpty()) return "";
        String bestKey = best(byKey);
        int bestCount = byKey.get(bestKey);
        if (byKey.size() > 1 && bestCount * 2 <= g.size()) return LibraryCleanup.VARIOUS_ARTISTS;
        return display.get(bestKey);
    }

    static String trackFromFileName(String path) {
        String name = LibraryCleanup.fileNameWithoutExtension(path);
        if (name == null) return null;
        Matcher m = FILE_TRACK_NO.matcher(name);
        if (!m.find()) return null;
        String n = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        try {
            int v = Integer.parseInt(n);
            return v > 0 ? String.valueOf(v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void count(Map<String, Map<String, Integer>> spellings, String value) {
        if (value == null || value.isEmpty() || LibraryCleanup.isUnknown(value)) return;
        String key = LibraryCleanup.normalize(value);
        if (key.isEmpty()) return;
        Map<String, Integer> m = spellings.get(key);
        if (m == null) {
            m = new HashMap<>();
            spellings.put(key, m);
        }
        inc(m, value);
    }

    private static Map<String, String> pickCanonical(Map<String, Map<String, Integer>> spellings) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : spellings.entrySet()) {
            if (e.getValue().size() > 1) result.put(e.getKey(), best(e.getValue()));
        }
        return result;
    }

    /** Most frequent value; ties go to the "richer" spelling (capitals, accents, "The ..."). */
    private static String best(Map<String, Integer> counts) {
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int c = e.getValue();
            if (c > bestCount || (c == bestCount && richness(e.getKey()) > richness(best))) {
                best = e.getKey();
                bestCount = c;
            }
        }
        return best;
    }

    private static int richness(String s) {
        if (s == null) return -1;
        int r = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isUpperCase(ch)) r += 2;
            if (ch > 127) r += 3;
        }
        if (s.toLowerCase(Locale.ROOT).startsWith("the ")) r += 1;
        if (s.equals(s.toUpperCase(Locale.ROOT)) && s.length() > 3) r -= 100; // ALL CAPS loses
        return r;
    }

    private static void inc(Map<String, Integer> m, String k) {
        Integer c = m.get(k);
        m.put(k, c == null ? 1 : c + 1);
    }

    private static Work w0(List<Work> g) {
        return g.get(0);
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    static String parentPath(String path) {
        if (path == null) return "";
        File parent = new File(path).getParentFile();
        return parent == null ? "" : parent.getPath();
    }
}
