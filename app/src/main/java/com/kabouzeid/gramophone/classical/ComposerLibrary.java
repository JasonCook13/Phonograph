package com.kabouzeid.gramophone.classical;

import android.content.Context;

import androidx.annotation.NonNull;

import com.kabouzeid.gramophone.loader.SongLoader;
import com.kabouzeid.gramophone.model.Song;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Your classical music as Composer -> Work -> Recording -> movements, built from the
 * songs on the device and matched against the chronologies.
 */
public final class ComposerLibrary {

    public static final class Recording {
        public final String performers;
        public final int year;
        public final String album;
        public final List<Song> tracks = new ArrayList<>();
        final List<Integer> movementNumbers = new ArrayList<>();

        Recording(String performers, int year, String album) {
            this.performers = performers == null ? "" : performers;
            this.year = year;
            this.album = album == null ? "" : album;
        }

        public String label() {
            StringBuilder sb = new StringBuilder(performers.isEmpty() ? "Unknown performers" : performers);
            if (year > 0) sb.append(" · ").append(year);
            return sb.toString();
        }
    }

    public static final class Work {
        public final String title;
        public final String category;
        public final Chronology.Work chrono;  // may be null
        public final List<Recording> recordings = new ArrayList<>();
        final String catalogueSort;

        Work(String title, String category, Chronology.Work chrono, String catalogueSort) {
            this.title = title;
            this.category = category;
            this.chrono = chrono;
            this.catalogueSort = catalogueSort;
        }

        public String catalogue() {
            return chrono != null && !chrono.catalogue.isEmpty() ? chrono.catalogue : catalogueSort;
        }

        /** The recording to play by default: the one with the most movements. */
        public Recording preferred() {
            Recording best = recordings.get(0);
            for (Recording r : recordings) if (r.tracks.size() > best.tracks.size()) best = r;
            return best;
        }

        public int trackCount() {
            int n = 0;
            for (Recording r : recordings) n += r.tracks.size();
            return n;
        }
    }

    public static final class Composer {
        public final String name;
        public final Chronology.Composer chrono;  // may be null
        public final List<Work> works = new ArrayList<>();

        Composer(String name, Chronology.Composer chrono) {
            this.name = name;
            this.chrono = chrono;
        }

        public String sortName() {
            if (chrono != null) return chrono.sortName();
            String n = Chronology.norm(name);
            int sp = n.lastIndexOf(' ');
            return sp >= 0 ? n.substring(sp + 1) : n;
        }

        public int matchedWorkCount() {
            int n = 0;
            for (Work w : works) if (w.chrono != null) n++;
            return n;
        }

        public int trackCount() {
            int n = 0;
            for (Work w : works) n += w.trackCount();
            return n;
        }
    }

    public enum Sort {COMPOSITION, CATALOGUE, TITLE}

    public final List<Composer> composers;

    private ComposerLibrary(List<Composer> composers) {
        this.composers = composers;
    }

    private static volatile ComposerLibrary cached;

    /** Builds (or returns the cached) library. Blocking: call off the UI thread. */
    @NonNull
    public static ComposerLibrary get(@NonNull Context context, boolean refresh) {
        ComposerLibrary c = cached;
        if (c != null && !refresh) return c;
        synchronized (ComposerLibrary.class) {
            if (cached == null || refresh) cached = build(context);
            return cached;
        }
    }

    public static void invalidate() {
        cached = null;
    }

    private static ComposerLibrary build(Context context) {
        Chronology chronology = ChronologyLoader.get(context);
        List<Song> songs = SongLoader.getAllSongsRaw(context);

        Map<String, Composer> composers = new LinkedHashMap<>();
        Map<String, Work> works = new HashMap<>();
        Map<String, Recording> recordings = new HashMap<>();

        for (Song s : songs) {
            ClassicalParser.Info info = ClassicalParser.parse(chronology, s.title, s.albumName, s.artistName,
                    s.albumArtistName, s.composer, s.data);
            if (info == null) continue;

            String composerKey = info.composer != null ? "#" + info.composer.name : Chronology.norm(info.composerName);
            Composer composer = composers.get(composerKey);
            if (composer == null) {
                composer = new Composer(info.composerName, info.composer);
                composers.put(composerKey, composer);
            }

            String workTitle = info.chrono != null ? info.chrono.title : info.work;
            String workKey = composerKey + "\u0000" + (info.chrono != null ? "#" + info.chrono.order : Chronology.norm(info.work));
            Work work = works.get(workKey);
            if (work == null) {
                String cat = info.catalogueKeys.isEmpty() ? "" : displayCatalogue(info.catalogueKeys.get(0));
                work = new Work(prettyWorkTitle(workTitle, info.work), info.category, info.chrono, cat);
                works.put(workKey, work);
                composer.works.add(work);
            }

            // One recording = one album in one folder.
            String folder = s.data == null ? "" : String.valueOf(new File(s.data).getParent());
            String recKey = workKey + "\u0000" + Chronology.norm(s.albumName) + "\u0000" + folder;
            Recording rec = recordings.get(recKey);
            if (rec == null) {
                rec = new Recording(s.artistName != null && !s.artistName.equals("<unknown>") ? s.artistName : "", s.year, s.albumName);
                recordings.put(recKey, rec);
                work.recordings.add(rec);
            }
            rec.tracks.add(s);
            rec.movementNumbers.add(info.movementNumber);
        }

        for (Composer c : composers.values()) {
            for (Work w : c.works) for (Recording r : w.recordings) sortMovements(r);
            sortWorks(c.works, Sort.COMPOSITION);
        }
        List<Composer> list = new ArrayList<>(composers.values());
        Collections.sort(list, (a, b) -> a.sortName().compareTo(b.sortName()));
        return new ComposerLibrary(list);
    }

    /** Movement number when known, otherwise disc/track order, otherwise file name. */
    private static void sortMovements(Recording r) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < r.tracks.size(); i++) idx.add(i);
        final List<Song> t = r.tracks;
        final List<Integer> mv = r.movementNumbers;
        Collections.sort(idx, (a, b) -> {
            int ma = mv.get(a), mb = mv.get(b);
            if (ma > 0 && mb > 0 && ma != mb) return ma - mb;
            if (t.get(a).trackNumber != t.get(b).trackNumber) return t.get(a).trackNumber - t.get(b).trackNumber;
            return String.valueOf(t.get(a).data).compareToIgnoreCase(String.valueOf(t.get(b).data));
        });
        List<Song> sorted = new ArrayList<>();
        List<Integer> sortedMv = new ArrayList<>();
        for (int i : idx) {
            sorted.add(t.get(i));
            sortedMv.add(mv.get(i));
        }
        r.tracks.clear();
        r.tracks.addAll(sorted);
        r.movementNumbers.clear();
        r.movementNumbers.addAll(sortedMv);
    }

    public static void sortWorks(List<Work> works, Sort sort) {
        Comparator<Work> byTitle = (a, b) -> naturalCompare(a.title, b.title);
        Comparator<Work> cmp;
        switch (sort) {
            case CATALOGUE:
                cmp = (a, b) -> {
                    String ca = a.catalogue(), cb = b.catalogue();
                    if (ca.isEmpty() != cb.isEmpty()) return ca.isEmpty() ? 1 : -1;
                    int c = naturalCompare(ca, cb);
                    return c != 0 ? c : byTitle.compare(a, b);
                };
                break;
            case TITLE:
                cmp = byTitle;
                break;
            default:
                // Catalogued works in order of composition; the rest after them, by title.
                cmp = (a, b) -> {
                    if ((a.chrono == null) != (b.chrono == null)) return a.chrono == null ? 1 : -1;
                    if (a.chrono != null) return a.chrono.order - b.chrono.order;
                    return byTitle.compare(a, b);
                };
        }
        Collections.sort(works, cmp);
    }

    /** Groups works by category, keeping each group in the given order; groups in order of first work. */
    public static LinkedHashMap<String, List<Work>> byCategory(List<Work> works) {
        LinkedHashMap<String, List<Work>> m = new LinkedHashMap<>();
        for (Work w : works) {
            List<Work> l = m.get(w.category);
            if (l == null) {
                l = new ArrayList<>();
                m.put(w.category, l);
            }
            l.add(w);
        }
        return m;
    }

    /** All tracks of these works, one recording each (the preferred one), in order. */
    public static List<Song> tracksInOrder(List<Work> works) {
        List<Song> songs = new ArrayList<>();
        for (Work w : works) songs.addAll(w.preferred().tracks);
        return songs;
    }

    /** Compares strings with numbers in numeric order ("No. 2" before "No. 10"). */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                long na = Long.parseLong(a.substring(si, Math.min(i, si + 18)));
                long nb = Long.parseLong(b.substring(sj, Math.min(j, sj + 18)));
                if (na != nb) return na < nb ? -1 : 1;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String displayCatalogue(String key) {
        int sp = key.indexOf(' ');
        if (sp < 0) return key;
        String sys = key.substring(0, sp);
        String num = key.substring(sp + 1);
        switch (sys) {
            case "op": return "Op. " + num.replace(' ', '/');
            case "k": return "K. " + num;
            case "d": return "D. " + num;
            case "hob": return "Hob. " + num.toUpperCase();
            case "woo": return "WoO " + num;
            case "wq": return "Wq " + num;
            case "buxwv": return "BuxWV " + num;
            case "trv": return "TrV " + num;
            default: return sys.toUpperCase() + " " + num;
        }
    }

    /** Prefer the chronology's title, but keep the track's when it is more specific. */
    private static String prettyWorkTitle(String chronoTitle, String trackWork) {
        if (chronoTitle == null || chronoTitle.isEmpty()) return trackWork;
        return chronoTitle;
    }
}
