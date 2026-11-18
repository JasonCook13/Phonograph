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
        /** Folder group: "Orchestral", "Chamber & Instrumental", ... */
        public final String group;
        public final Chronology.Work chrono;  // may be null
        public final List<Recording> recordings = new ArrayList<>();
        final String catalogueSort;

        Work(String title, String group, String category, Chronology.Work chrono, String catalogueSort) {
            this.title = title;
            this.group = group;
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

    // ---------------------------------------------------------------------------------------
    // One composer, many spellings
    // ---------------------------------------------------------------------------------------

    /** Groups composer names that refer to the same person and picks one display name for each. */
    static final class NameMerger {
        static final class Entry {
            String key, displayName, surname, given;
            Chronology.Composer chrono;
            Entry parent;  // after resolve(): the entry this one was merged into (or itself)
        }

        private final Map<String, Entry> byRaw = new LinkedHashMap<>();

        private static String rawKey(String name, Chronology.Composer chrono) {
            return chrono != null ? "#" + chrono.name : "?" + Chronology.norm(clean(name));
        }

        void add(String name, Chronology.Composer chrono) {
            String k = rawKey(name, chrono);
            if (byRaw.containsKey(k)) return;
            Entry e = new Entry();
            e.chrono = chrono;
            e.displayName = chrono != null ? chrono.name : clean(name);
            // Surname = last word as written, so "Saint-Saëns" stays one surname ("saint saens").
            String d = e.displayName.trim();
            int sp = d.lastIndexOf(' ');
            e.surname = Chronology.norm(sp >= 0 ? d.substring(sp + 1) : d);
            e.given = sp >= 0 ? Chronology.norm(d.substring(0, sp)) : "";
            e.key = k;
            e.parent = e;
            byRaw.put(k, e);
        }

        void resolve() {
            List<Entry> all = new ArrayList<>(byRaw.values());
            // Known composers and fuller names first, so others merge into them.
            Collections.sort(all, (a, b) -> {
                if ((a.chrono != null) != (b.chrono != null)) return a.chrono != null ? -1 : 1;
                return b.given.length() - a.given.length();
            });
            for (int i = 0; i < all.size(); i++) {
                Entry e = all.get(i);
                if (e.parent != e || e.chrono != null) continue;
                for (int j = 0; j < i; j++) {
                    Entry t = all.get(j);
                    if (t.parent != t || !t.surname.equals(e.surname)) continue;
                    if (sameGiven(t.given, e.given)) {
                        e.parent = t;
                        break;
                    }
                }
            }
        }

        Entry of(String name, Chronology.Composer chrono) {
            Entry e = byRaw.get(rawKey(name, chrono));
            while (e.parent != e) e = e.parent;
            return e;
        }

        /** "" matches anything; "j s" ~ "johann sebastian"; "mily" ~ "mily alexeyevich"; small typos. */
        static boolean sameGiven(String a, String b) {
            if (a.isEmpty() || b.isEmpty() || a.equals(b)) return true;
            if (a.startsWith(b + " ") || b.startsWith(a + " ")) return true;
            String[] x = a.split(" "), y = b.split(" ");
            if (x[0].length() == 1 || y[0].length() == 1) {
                int n = Math.min(x.length, y.length);
                for (int i = 0; i < n; i++) if (x[i].charAt(0) != y[i].charAt(0)) return false;
                return true;
            }
            return Chronology.editDistance(a, b) <= 2;
        }

        /** "BALAKIREV, MILY (1837-1910)" -> "Mily Balakirev". */
        static String clean(String name) {
            if (name == null) return "";
            String n = name.replaceAll("\\(.*?\\)|\\[.*?\\]", " ").replaceAll("\\s+", " ").trim();
            n = n.replaceAll("[,;\\-\\s]+$", "");
            int comma = n.indexOf(',');
            if (comma > 0 && n.indexOf(',', comma + 1) < 0) {
                n = n.substring(comma + 1).trim() + " " + n.substring(0, comma).trim();
            }
            return ClassicalParser.tidyName(n.trim());
        }
    }

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

        // Pass 1: parse every song, then merge spellings of the same composer
        // ("Balakirev (1837-1910)", "Balakirev, Mily", "Mily Balakirev"; "Dvorak" / "Dvořák").
        List<Song> classicalSongs = new ArrayList<>();
        List<ClassicalParser.Info> infos = new ArrayList<>();
        for (Song s : songs) {
            ClassicalParser.Info info = ClassicalParser.parse(chronology, s.title, s.albumName, s.artistName,
                    s.albumArtistName, s.composer, s.data);
            if (info == null) continue;
            classicalSongs.add(s);
            infos.add(info);
        }
        NameMerger names = new NameMerger();
        for (ClassicalParser.Info info : infos) names.add(info.composerName, info.composer);
        names.resolve();

        for (int i = 0; i < classicalSongs.size(); i++) {
            Song s = classicalSongs.get(i);
            ClassicalParser.Info info = infos.get(i);
            NameMerger.Entry who = names.of(info.composerName, info.composer);
            String composerKey = who.key;
            Composer composer = composers.get(composerKey);
            if (composer == null) {
                composer = new Composer(who.displayName, who.chrono);
                composers.put(composerKey, composer);
            }

            String workTitle = info.chrono != null ? info.chrono.title : info.work;
            String workKey = composerKey + "\u0000" + (info.chrono != null ? "#" + info.chrono.order : Chronology.norm(info.work));
            Work work = works.get(workKey);
            if (work == null) {
                String cat = info.catalogueKeys.isEmpty() ? "" : displayCatalogue(info.catalogueKeys.get(0));
                String[] where = folderLocation(s.data);
                String category = where != null ? where[1] : info.category;
                String group = where != null ? where[0] : groupOf(info.category);
                work = new Work(prettyWorkTitle(workTitle, info.work), group, category, info.chrono, cat);
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

    // ---------------------------------------------------------------------------------------
    // Type of work from the library folders: Classical/<Composer>/<Group>/<Type>/...
    // ---------------------------------------------------------------------------------------

    /** Groups and their types in display order (the tabs on a composer's page follow this). */
    private static final String[][] TYPES = {
            {"Orchestral", "Symphonies", "Overtures", "Tone Poems", "Orchestral Suites", "Serenades & Divertimenti",
                    "Orchestral Works", "Dances", "Waltzes", "Polkas", "Galops", "Quadrilles", "Marches"},
            {"Concertos", "Piano Concertos", "Keyboard Concertos", "Violin Concertos", "Cello Concertos", "Cello Concerti",
                    "Viola d'Amore Concertos", "Wind & Other Concertos", "Multiple Concertos", "Brandenburg Concertos",
                    "Concerti Grossi", "Concertante Works", "Concertos"},
            {"Chamber & Instrumental", "String Quartets", "Quartets", "Piano Quartets", "String Quintets", "Piano Quintets",
                    "Quintets", "Sextets, Septets & Octets", "Piano Trios", "String Trios", "Trios", "Trio Sonatas",
                    "Violin Sonatas", "Cello Sonatas", "Sonatas", "Other Sonatas", "Solo Violin Sonatas & Partitas",
                    "Cello Suites", "Suites", "Partitas", "Musical Offering & Art of Fugue", "Chamber Works"},
            {"Piano & Keyboard", "Piano Sonatas", "Variations", "Preludes & Fugues", "Well-Tempered Clavier", "Preludes",
                    "Études", "Nocturnes", "Ballades", "Scherzos", "Impromptus", "Waltzes", "Mazurkas", "Polonaises",
                    "Rhapsodies", "Dances", "Marches", "Galops", "Polkas", "Rags", "Rags & two-steps", "French Suites",
                    "Keyboard Suites", "Partitas", "Inventions & Sinfonias", "Paraphrases & Transcriptions",
                    "Paraphrases & fantasies", "Piano Works", "Keyboard Works", "Keyboard & Organ", "Organ Works"},
            {"Vocal & Choral", "Masses", "Masses & Requiems", "Requiems", "Passions", "Oratorios", "Cantatas",
                    "Choral & Sacred Works", "Choral and sacred", "Arias", "Songs", "Vocal Ensembles", "Canons", "Variations"},
            {"Stage Works", "Operas", "Ballets", "Incidental Music"},
            {"Other Works"},
            {"Albums & Film", "Film Scores", "Film and incidental music", "Albums", "Albums (concert works)",
                    "Studio albums", "Collaborations", "Band"},
    };

    /** {group, type} from the folders: Classical/<Composer>/<Group>/<Type>/... The type is the group
     *  itself when the next folder is a performer or a work. Null when the file is not in that layout. */
    static String[] folderLocation(String path) {
        if (path == null) return null;
        String[] seg = path.replace('\\', '/').split("/");
        for (int i = 0; i + 3 < seg.length; i++) {
            if (!seg[i].equalsIgnoreCase("Classical")) continue;
            String group = seg[i + 2];
            for (String[] g : TYPES) {
                if (!g[0].equalsIgnoreCase(group)) continue;
                // seg[i + 3] is a folder only if something follows it
                if (i + 4 < seg.length) {
                    for (int t = 1; t < g.length; t++) if (g[t].equalsIgnoreCase(seg[i + 3])) return new String[]{g[0], g[t]};
                }
                return new String[]{g[0], g[0]};
            }
            return null;
        }
        return null;
    }

    /** The group a type of work belongs to (first match), or "Other Works". */
    static String groupOf(String category) {
        if (category != null) {
            for (String[] g : TYPES) {
                for (String t : g) if (t.equalsIgnoreCase(category)) return g[0];
            }
        }
        return "Other Works";
    }

    /** Groups in display order. */
    public static List<String> groupOrder() {
        List<String> out = new ArrayList<>();
        for (String[] g : TYPES) out.add(g[0]);
        return out;
    }

    /** Short tab label for a group. */
    public static String groupLabel(String group) {
        switch (group) {
            case "Chamber & Instrumental": return "Chamber";
            case "Piano & Keyboard": return "Piano";
            case "Vocal & Choral": return "Vocal";
            case "Stage Works": return "Stage";
            case "Other Works": return "Other";
            default: return group;
        }
    }

    /** Position of a type inside its group; the group itself (loose works) and unknown types go last. */
    public static int categoryRank(String group, String category) {
        for (String[] g : TYPES) {
            if (!g[0].equalsIgnoreCase(group)) continue;
            for (int t = 1; t < g.length; t++) if (g[t].equalsIgnoreCase(category)) return t;
            return g[0].equalsIgnoreCase(category) ? 1000 : 500;
        }
        return 500;
    }

    /** Works by group (display order), then by type inside each group (display order). */
    public static LinkedHashMap<String, LinkedHashMap<String, List<Work>>> byGroup(List<Work> works) {
        LinkedHashMap<String, LinkedHashMap<String, List<Work>>> out = new LinkedHashMap<>();
        List<String> groups = groupOrder();
        for (String g : groups) out.put(g, new LinkedHashMap<>());
        for (Work w : works) {
            String g = w.group == null ? "Other Works" : w.group;
            LinkedHashMap<String, List<Work>> types = out.get(g);
            if (types == null) {
                types = new LinkedHashMap<>();
                out.put(g, types);
            }
            String c = w.category == null || w.category.isEmpty() ? g : w.category;
            List<Work> l = types.get(c);
            if (l == null) {
                l = new ArrayList<>();
                types.put(c, l);
            }
            l.add(w);
        }
        LinkedHashMap<String, LinkedHashMap<String, List<Work>>> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, List<Work>>> e : out.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            final String g = e.getKey();
            List<String> keys = new ArrayList<>(e.getValue().keySet());
            Collections.sort(keys, (a, b) -> {
                int c = categoryRank(g, a) - categoryRank(g, b);
                return c != 0 ? c : a.compareToIgnoreCase(b);
            });
            LinkedHashMap<String, List<Work>> types = new LinkedHashMap<>();
            for (String k : keys) types.put(k, e.getValue().get(k));
            sorted.put(g, types);
        }
        return sorted;
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
