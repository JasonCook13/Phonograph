package com.kabouzeid.gramophone.classical;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "Works in Order of Composition" data (one entry per composer), with lookups:
 * which composer a name refers to, and which catalogued work a track belongs to.
 */
public final class Chronology {

    public static final class Work {
        public final int order;          // position in order of composition (1 = earliest)
        public final String catalogue;   // "BWV 1046"
        public final String title;
        public final String date;        // as written in the chronology
        public final double year;        // NaN if unknown
        public final String genre;
        private final List<String> alternatives;
        // Built lazily (see Composer.index) so loading 25k works stays fast.
        List<String> keys;
        Set<String> tokens;
        Set<String> numbers;

        public Work(int order, String catalogue, List<String> alternatives, String title, String date, double year, String genre) {
            this.order = order;
            this.catalogue = catalogue == null ? "" : catalogue;
            this.title = title == null ? "" : title;
            this.date = date == null ? "" : date;
            this.year = year;
            this.genre = genre == null ? "" : genre;
            this.alternatives = alternatives;
        }

        void index() {
            if (keys != null) return;
            List<String> k = new ArrayList<>(Catalogue.keys(catalogue));
            if (alternatives != null) for (String a : alternatives) k.addAll(Catalogue.keys(a));
            k.addAll(Catalogue.keys(title));
            tokens = tokens(title);
            numbers = numbers(title);
            keys = k;
        }
    }

    public static final class Composer {
        public final String name;
        public final List<String> aliases;
        public final List<Work> works;
        final Map<String, List<Work>> byKey = new HashMap<>();
        final Map<String, Work> titleCache = new HashMap<>();
        private boolean indexed;

        public Composer(String name, List<String> aliases, List<Work> works) {
            this.name = name;
            this.aliases = aliases;
            this.works = works;
        }

        private void index() {
            if (indexed) return;
            indexed = true;
            for (Work w : works) {
                w.index();
                for (String k : w.keys) {
                    List<Work> l = byKey.get(k);
                    if (l == null) {
                        l = new ArrayList<>();
                        byKey.put(k, l);
                    }
                    l.add(w);
                }
            }
        }

        /** Surname used for sorting ("Bach", "Vaughan Williams"). */
        public String sortName() {
            String n = norm(name);
            for (String a : aliases) {
                if (!a.contains(" ") && n.endsWith(" " + a)) return a;
            }
            if (n.endsWith("vaughan williams")) return "vaughan williams";
            int sp = n.lastIndexOf(' ');
            return sp >= 0 ? n.substring(sp + 1) : n;
        }

        /**
         * Best matching work for a track's work title (and catalogue keys), or null.
         * Catalogue numbers win; otherwise titles must share their numbers and most words.
         */
        public Work match(String workTitle, List<String> catalogueKeys) {
            return match(workTitle, catalogueKeys, false);
        }

        /**
         * @param wholeAlbum true when {@code workTitle} is an album name: then the catalogue
         *                   title's words must be in the album name ("Star Wars: The Empire
         *                   Strikes Back" -> "The Empire Strikes Back").
         */
        public synchronized Work match(String workTitle, List<String> catalogueKeys, boolean wholeAlbum) {
            index();
            if (catalogueKeys != null) {
                for (String k : catalogueKeys) {
                    List<Work> l = byKey.get(k);
                    if (l != null && !l.isEmpty()) return pickBest(l, workTitle);
                }
            }
            if (workTitle == null || workTitle.trim().isEmpty()) return null;
            String cacheKey = (wholeAlbum ? "@" : "") + norm(workTitle);
            if (titleCache.containsKey(cacheKey)) return titleCache.get(cacheKey);
            Work best = null;
            double bestScore = 0;
            Set<String> t = tokens(workTitle);
            Set<String> n = numbers(workTitle);
            if (!t.isEmpty()) {
                for (Work w : works) {
                    if (!w.numbers.containsAll(n) || !n.containsAll(w.numbers)) continue;
                    double s = wholeAlbum ? albumSimilarity(t, w.tokens) : similarity(t, w.tokens);
                    if (s > bestScore) {
                        bestScore = s;
                        best = w;
                    }
                }
            }
            Work result = bestScore >= 0.75 ? best : null;
            titleCache.put(cacheKey, result);
            return result;
        }

        private static Work pickBest(List<Work> candidates, String workTitle) {
            if (candidates.size() == 1 || workTitle == null) return candidates.get(0);
            Set<String> t = tokens(workTitle);
            Work best = candidates.get(0);
            double bestScore = -1;
            for (Work w : candidates) {
                double s = similarity(t, w.tokens);
                if (s > bestScore) {
                    bestScore = s;
                    best = w;
                }
            }
            return best;
        }
    }

    public final List<Composer> composers;
    private final Map<String, Composer> byAlias = new HashMap<>();

    public Chronology(List<Composer> composers) {
        this.composers = composers;
        // Full names first, so that a shared surname ("Bach") goes to the composer who lists it explicitly.
        for (Composer c : composers) byAlias.put(norm(c.name), c);
        for (Composer c : composers) {
            for (String a : c.aliases) {
                if (!byAlias.containsKey(a)) byAlias.put(a, c);
            }
        }
    }

    /**
     * The composer a name refers to: "Johann Sebastian Bach", "Bach", "J.S. Bach",
     * "Bach, Johann Sebastian", "BACH, JOHANN SEBASTIAN (1685-1750)". Null if unknown.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Object> findCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NOT_FOUND = new Object();

    /** Same answer for the same text, so it's worked out once per distinct name (tags repeat a lot). */
    public Composer findComposer(String name) {
        if (name == null) return null;
        Object hit = findCache.get(name);
        if (hit != null) return hit == NOT_FOUND ? null : (Composer) hit;
        Composer found = findComposerUncached(name);
        findCache.put(name, found == null ? NOT_FOUND : found);
        return found;
    }

    private Composer findComposerUncached(String name) {
        String raw = name.replaceAll("\\(.*?\\)", " ").trim();
        if (raw.isEmpty()) return null;
        // "Last, First" -> "First Last"
        int comma = raw.indexOf(',');
        if (comma > 0 && raw.indexOf(',', comma + 1) < 0) {
            String last = raw.substring(0, comma).trim();
            String given = raw.substring(comma + 1).trim();
            Composer c = byAlias.get(norm(given + " " + last));
            if (c != null) return c;
            // Misspelled given names ("Haydn, Joesph"): same surname, given names within two edits,
            // so "Bach, Johann Christoph Friedrich" doesn't become Johann Sebastian.
            String nl = norm(last), ng = norm(given);
            Composer bySurname = byAlias.get(nl);  // "rachmaninov" -> Rachmaninoff
            for (Composer k : composers) {
                String full = norm(k.name);
                String kGiven;
                if (full.endsWith(" " + nl)) kGiven = full.substring(0, full.length() - nl.length() - 1);
                else if (k == bySurname && full.lastIndexOf(' ') > 0) kGiven = full.substring(0, full.lastIndexOf(' '));
                else continue;
                // "Joesph" ~ "Joseph"; "Georg" ~ "Georg Philipp"
                if (editDistance(ng, kGiven) <= 2 || kGiven.startsWith(ng + " ") || ng.startsWith(kGiven + " ")) return k;
            }
        }
        Composer c = byAlias.get(norm(raw));
        if (c != null) return c;
        // "J.S. Bach" / "JS Bach" -> initials + surname
        String n = norm(raw);
        for (Composer k : composers) {
            String full = norm(k.name);
            if (n.length() > 3 && initialsMatch(n, full)) return k;
        }
        return null;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static boolean initialsMatch(String candidate, String full) {
        String[] c = candidate.split(" ");
        String[] f = full.split(" ");
        if (c.length < 2 || !c[c.length - 1].equals(f[f.length - 1])) return false;
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < f.length - 1; i++) initials.append(f[i].charAt(0));
        StringBuilder given = new StringBuilder();
        for (int i = 0; i < c.length - 1; i++) given.append(c[i]);
        return given.length() > 0 && given.length() <= 3 && initials.toString().equals(given.toString());
    }

    // -------------------------------------------------------------------------------------

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d{1,4}\\b");
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "in", "for", "and", "the", "of", "a", "an", "no", "nr", "op", "opus", "major", "minor",
            "dur", "moll", "maj", "min", "with", "de", "la", "le", "et", "und", "fur", "flat", "sharp",
            "bwv", "hwv", "kv", "k", "d", "rv", "hob", "woo", "sharp", "version"));

    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = DIACRITICS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
        n = n.replace("&", " and ").replace("♭", " flat ").replace("♯", " sharp ");
        return NON_ALNUM.matcher(n).replaceAll(" ").trim();
    }

    static Set<String> tokens(String s) {
        Set<String> t = new HashSet<>();
        for (String w : norm(s).split(" ")) {
            // Single letters are mostly keys ("in F"), often left out of one title or the other.
            if (w.length() < 2 || STOP.contains(w) || NUMBER.matcher(w).matches()) continue;
            t.add(singular(w));
        }
        return t;
    }

    /** Work numbers ("No. 3", "Symphony 5"); catalogue numbers are matched separately. */
    static Set<String> numbers(String s) {
        Set<String> n = new HashSet<>();
        String stripped = s == null ? "" : s;
        for (String k : Catalogue.keys(stripped)) {
            // Remove catalogue numbers so "BWV 1046" doesn't count as a work number.
            String num = k.substring(k.indexOf(' ') + 1);
            stripped = stripped.replaceAll("(?i)\\b" + Pattern.quote(num) + "\\b", " ");
        }
        Matcher m = NUMBER.matcher(norm(stripped));
        while (m.find()) {
            String v = m.group().replaceFirst("^0+(?=\\d)", "");
            if (v.length() == 4 && (v.startsWith("1") || v.startsWith("2"))) continue; // years
            n.add(v);
        }
        return n;
    }

    private static String singular(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static double albumSimilarity(Set<String> album, Set<String> work) {
        if (album.isEmpty() || work.isEmpty()) return 0;
        int common = 0;
        for (String x : work) if (album.contains(x)) common++;
        double precision = (double) common / work.size();
        double coverage = (double) common / album.size();
        return 0.5 * precision + 0.5 * coverage;
    }

    /** How much of the chronology title's words the library title contains (0..1). */
    public static double similarity(String libraryTitle, String chronoTitle) {
        Set<String> a = tokens(chronoTitle), b = tokens(libraryTitle);
        if (a.isEmpty()) return 1;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        return (double) common / a.size();
    }

    private static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        // Share of the track's words found in the catalogue title, lightly penalising extra words.
        double coverage = (double) common / a.size();
        double precision = (double) common / b.size();
        return 0.8 * coverage + 0.2 * precision;
    }
}
