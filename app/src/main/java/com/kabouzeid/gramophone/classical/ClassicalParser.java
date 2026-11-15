package com.kabouzeid.gramophone.classical;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a track's tags and works out the classical structure behind them:
 * composer, work, movement, catalogue number and kind of work.
 *
 * Handles the usual ways classical files are tagged:
 * - composer tag, or composer folders like "BACH, JOHANN SEBASTIAN (1685-1750)"
 * - "Composer: Work: I. Movement", "Work - II. Movement", "No. 1 in F, Allegro"
 * - set albums ("Brandenburg Concertos 1-4") whose tracks only say "No. 1 in F"
 */
public final class ClassicalParser {

    public static final class Info {
        public Chronology.Composer composer;  // null if not classical / composer unknown
        public String composerName;           // display name
        public String work;                   // "Brandenburg Concerto No. 1 in F"
        public String movement;               // "Allegro" (may be empty)
        public int movementNumber;            // from roman numerals, 0 if unknown
        public List<String> catalogueKeys;
        public String category;               // "Concertos", "String Quartets", ...
        public Chronology.Work chrono;        // matched catalogued work, or null
    }

    /** "BACH, JOHANN SEBASTIAN (1685-1750)" */
    private static final Pattern COMPOSER_FOLDER = Pattern.compile(
            "^\\s*([^,()]+?)\\s*,\\s*([^()]+?)\\s*\\(\\s*c?\\.?\\s*\\d{3,4}\\s*[-–]\\s*(?:c?\\.?\\s*\\d{3,4})?\\s*\\)\\s*$");
    private static final Pattern ROMAN_MOVEMENT = Pattern.compile("^\\s*([IVX]{1,5})[.:)]\\s+(.+)$");
    private static final Pattern NUMBERED_MOVEMENT = Pattern.compile("^\\s*(\\d{1,2})[.:)]\\s+(.+)$");
    private static final Pattern STARTS_WITH_NO = Pattern.compile("^\\s*(?:No\\.?|Nr\\.?|Number)\\s*\\d", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_TRACK = Pattern.compile("^\\s*\\d{1,3}\\s*[-._)]\\s*");
    private static final Pattern MOVEMENT_WORDS = Pattern.compile(
            "\\b(allegr\\w*|adagi\\w*|andant\\w*|prest\\w*|larg\\w*|lent\\w*|grave|vivace|moderato|menuet\\w*|minuet\\w*|"
                    + "scherz\\w*|rondo|rondeau|finale|tempo|maestoso|sostenuto|cantabile|affettuoso|siciliana|siciliano|"
                    + "gigue|giga|sarabande|sarabanda|courante|corrente|allemande|allemanda|gavotte|bourr\\w*|badinerie|"
                    + "polonaise|passepied|chaconne|ciaccona|passacaglia|air|aria|recitativ\\w*|chorus|chor|choral|coro|"
                    + "duetto|duet|sinfonia|ouverture|overture|prelude|praeludium|preludio|fugue|fuga|toccata|intermezzo|"
                    + "introduction|introduzione|theme|thema|variation\\w*|marcia|march|romanze|romance|trio|cadenza|"
                    + "pastorale|spiritoso|animato|agitato|vivo|assai|ma non troppo|con brio|con moto|un poco)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_WORD = Pattern.compile(
            "^(.*?\\b(concertos|concerti|sonatas|sonate|quartets|quintets|trios|symphonies|suites|partitas|"
                    + "preludes|etudes|études|nocturnes|cantatas|overtures|serenades|divertimenti|sinfonias|inventions|"
                    + "impromptus|ballades|mazurkas|waltzes|polonaises|duets|sextets|octets|masses|motets|madrigals))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String[][] CATEGORIES = {
            {"string quartet", "String Quartets"},
            {"piano quartet", "Piano Quartets"},
            {"piano trio", "Piano Trios"},
            {"string trio", "String Trios"},
            {"piano quintet", "Piano Quintets"},
            {"string quintet", "String Quintets"},
            {"clarinet quintet", "Quintets"},
            {"quintet", "Quintets"},
            {"sextet", "Sextets & Octets"},
            {"octet", "Sextets & Octets"},
            {"quartet", "Quartets"},
            {"trio sonata", "Trio Sonatas"},
            {"trio", "Trios"},
            {"symphon", "Symphonies"},
            {"sinfonia concertante", "Concertos"},
            {"concerto grosso", "Concertos"},
            {"concert", "Concertos"},
            {"piano sonata", "Piano Sonatas"},
            {"violin sonata", "Violin Sonatas"},
            {"sonata for violin", "Violin Sonatas"},
            {"cello sonata", "Cello Sonatas"},
            {"sonata for cello", "Cello Sonatas"},
            {"keyboard sonata", "Keyboard Sonatas"},
            {"harpsichord", "Keyboard Works"},
            {"sonata", "Sonatas"},
            {"sonate", "Sonatas"},
            {"partita", "Suites & Partitas"},
            {"suite", "Suites & Partitas"},
            {"mass", "Masses & Requiems"},
            {"missa", "Masses & Requiems"},
            {"messe", "Masses & Requiems"},
            {"requiem", "Masses & Requiems"},
            {"magnificat", "Sacred Works"},
            {"stabat mater", "Sacred Works"},
            {"te deum", "Sacred Works"},
            {"vespers", "Sacred Works"},
            {"motet", "Sacred Works"},
            {"psalm", "Sacred Works"},
            {"cantata", "Cantatas"},
            {"kantate", "Cantatas"},
            {"oratori", "Oratorios & Passions"},
            {"passion", "Oratorios & Passions"},
            {"opera", "Operas"},
            {"overture", "Overtures"},
            {"ouverture", "Overtures"},
            {"ballet", "Ballets"},
            {"lieder", "Songs"},
            {"lied", "Songs"},
            {"song", "Songs"},
            {"melodie", "Songs"},
            {"madrigal", "Madrigals"},
            {"etude", "Piano Works"},
            {"prelude", "Piano Works"},
            {"nocturne", "Piano Works"},
            {"mazurka", "Piano Works"},
            {"waltz", "Piano Works"},
            {"valse", "Piano Works"},
            {"ballade", "Piano Works"},
            {"impromptu", "Piano Works"},
            {"polonaise", "Piano Works"},
            {"scherzo", "Piano Works"},
            {"fugue", "Organ & Keyboard"},
            {"toccata", "Organ & Keyboard"},
            {"chorale", "Organ & Keyboard"},
            {"organ", "Organ & Keyboard"},
            {"variation", "Variations"},
            {"serenade", "Serenades & Divertimenti"},
            {"divertimento", "Serenades & Divertimenti"},
            {"tone poem", "Orchestral Works"},
            {"symphonic poem", "Orchestral Works"},
            {"rhapsod", "Orchestral Works"},
            {"dances", "Orchestral Works"},
    };

    private ClassicalParser() {
    }

    /**
     * @return info for a classical track, or null if nothing marks this track as classical.
     */
    public static Info parse(Chronology chronology, String title, String album, String artist,
                             String albumArtist, String composerTag, String path) {
        Chronology.Composer composer = null;
        String composerName = null;

        // 1. Composer tag.
        if (!isBlank(composerTag)) {
            composer = chronology == null ? null : chronology.findComposer(composerTag);
            composerName = composer != null ? composer.name : tidyName(composerTag);
        }
        // 2. A "LAST, FIRST (dates)" folder above the file.
        if (composerName == null) {
            String folder = composerFolder(path);
            if (folder != null) {
                composer = chronology == null ? null : chronology.findComposer(folder);
                composerName = composer != null ? composer.name : tidyName(folderToName(folder));
            }
        }
        // 3. "Composer: Work" title prefix.
        String t = title == null ? "" : title.trim();
        if (chronology != null) {
            int colon = t.indexOf(':');
            if (colon > 0 && colon < 40) {
                Chronology.Composer c = chronology.findComposer(t.substring(0, colon));
                if (c != null && (composer == null || c == composer)) {
                    composer = c;
                    composerName = c.name;
                    t = t.substring(colon + 1).trim();
                }
            }
        }
        // 4. Album artist / artist that is a known composer (full name only, to avoid
        //    "Williams" style false matches).
        if (composerName == null && chronology != null) {
            for (String candidate : new String[]{albumArtist, artist}) {
                if (isBlank(candidate)) continue;
                Chronology.Composer c = chronology.findComposer(candidate);
                if (c != null && Chronology.norm(candidate).contains(" ")) {
                    composer = c;
                    composerName = c.name;
                    break;
                }
            }
        }
        if (composerName == null) return null;

        Info info = new Info();
        info.composer = composer;
        info.composerName = composerName;

        // Work / movement.
        t = LEADING_TRACK.matcher(t).replaceFirst("");
        String work = t;
        String movement = "";
        int split = splitPoint(t);
        if (split > 0) {
            work = t.substring(0, split).trim();
            movement = t.substring(split + 1).trim();
            if (movement.startsWith("-")) movement = movement.substring(1).trim();
        }
        work = work.replaceAll("[\\s,:\\-–]+$", "");
        Matcher rm = ROMAN_MOVEMENT.matcher(movement);
        if (rm.matches()) {
            info.movementNumber = roman(rm.group(1));
            movement = rm.group(2).trim();
        } else {
            Matcher nm = NUMBERED_MOVEMENT.matcher(movement);
            if (nm.matches()) {
                info.movementNumber = Integer.parseInt(nm.group(1));
                movement = nm.group(2).trim();
            }
        }

        // "No. 1 in F" on an album "Brandenburg Concertos 1-4" -> "Brandenburg Concerto No. 1 in F".
        if (STARTS_WITH_NO.matcher(work).find() && album != null) {
            Matcher sm = SET_WORD.matcher(album);
            if (sm.find()) work = singularSet(sm.group(1).trim()) + " " + work;
        }
        if (work.isEmpty()) work = album == null || album.isEmpty() ? t : album;

        info.work = work;
        info.movement = movement;
        info.catalogueKeys = Catalogue.keys(work + " " + (movement.isEmpty() ? "" : ""));
        if (info.catalogueKeys.isEmpty()) {
            List<String> albumKeys = Catalogue.keys(album);
            if (albumKeys.size() == 1) info.catalogueKeys = albumKeys;
        }

        if (composer != null) {
            info.chrono = composer.match(work, info.catalogueKeys);
            if (info.chrono == null && album != null && !album.isEmpty()) {
                // Whole album as one work (film scores, single-work albums).
                info.chrono = composer.match(album, Catalogue.keys(album), true);
                if (info.chrono != null && split <= 0) {
                    info.movement = t;
                    info.work = info.chrono.title;
                }
            }
        }
        info.category = category(work + " " + (album == null ? "" : album), info.chrono);
        return info;
    }

    /**
     * Just the composer's display name if this track is classical (same rules as {@link #parse}
     * but without matching the work), else null. {@code chronology} may be null.
     */
    public static String composerOf(Chronology chronology, String title, String artist,
                                    String albumArtist, String composerTag, String path) {
        if (!isBlank(composerTag)) {
            Chronology.Composer c = chronology == null ? null : chronology.findComposer(composerTag);
            return c != null ? c.name : tidyName(composerTag);
        }
        String folder = composerFolder(path);
        if (folder != null) {
            Chronology.Composer c = chronology == null ? null : chronology.findComposer(folder);
            return c != null ? c.name : tidyName(folderToName(folder));
        }
        if (chronology == null) return null;
        String t = title == null ? "" : title.trim();
        int colon = t.indexOf(':');
        if (colon > 0 && colon < 40) {
            Chronology.Composer c = chronology.findComposer(t.substring(0, colon));
            if (c != null) return c.name;
        }
        for (String candidate : new String[]{albumArtist, artist}) {
            if (isBlank(candidate)) continue;
            Chronology.Composer c = chronology.findComposer(candidate);
            if (c != null && Chronology.norm(candidate).contains(" ")) return c.name;
        }
        return null;
    }

    /** Where the work ends and the movement begins, or -1. */
    static int splitPoint(String t) {
        // "Work: I. Allegro" / "Work: Allegro"
        int colon = t.lastIndexOf(':');
        if (colon > 0 && colon < t.length() - 1 && isMovement(t.substring(colon + 1))) return colon;
        // "Work - II. Andante"
        int dash = Math.max(t.lastIndexOf(" - "), t.lastIndexOf(" – "));
        if (dash > 0 && isMovement(t.substring(dash + 2))) return dash + 1;
        // "No. 1 in F, Allegro"
        int comma = t.lastIndexOf(", ");
        if (comma > 0 && isMovement(t.substring(comma + 1))) return comma;
        // "Work: anything" with a roman numeral start after the colon
        int firstColon = t.indexOf(':');
        if (firstColon > 0 && ROMAN_MOVEMENT.matcher(t.substring(firstColon + 1)).matches()) return firstColon;
        return -1;
    }

    private static boolean isMovement(String s) {
        String m = s.trim();
        if (m.isEmpty()) return false;
        if (ROMAN_MOVEMENT.matcher(m).matches() || NUMBERED_MOVEMENT.matcher(m).matches()) return true;
        // Movement names are short and made of tempo / form words.
        return m.length() <= 60 && MOVEMENT_WORDS.matcher(Chronology.norm(m)).find() && !Catalogue.looksClassical(m)
                && !m.toLowerCase(Locale.ROOT).matches(".*\\b(no\\.?|op\\.?)\\s*\\d.*");
    }

    static String category(String text, Chronology.Work chrono) {
        String l = Chronology.norm(text);
        String chronoTitle = chrono == null ? "" : Chronology.norm(chrono.title);
        for (String[] c : CATEGORIES) {
            if (chronoTitle.contains(c[0])) return c[1];
        }
        for (String[] c : CATEGORIES) {
            if (l.contains(c[0])) return c[1];
        }
        if (chrono != null && !chrono.genre.isEmpty()) return chrono.genre;
        return "Other Works";
    }

    private static String singularSet(String phrase) {
        String lower = phrase.toLowerCase(Locale.ROOT);
        if (lower.endsWith("symphonies")) return phrase.substring(0, phrase.length() - 3) + "y";
        if (lower.endsWith("concerti")) return phrase.substring(0, phrase.length() - 1) + "o";
        if (lower.endsWith("divertimenti")) return phrase.substring(0, phrase.length() - 1) + "o";
        if (lower.endsWith("masses")) return phrase.substring(0, phrase.length() - 2);
        if (lower.endsWith("sonate")) return phrase.substring(0, phrase.length() - 1) + "a";
        if (lower.endsWith("s")) return phrase.substring(0, phrase.length() - 1);
        return phrase;
    }

    private static int roman(String r) {
        int total = 0, prev = 0;
        for (int i = r.length() - 1; i >= 0; i--) {
            int v;
            switch (r.charAt(i)) {
                case 'I': v = 1; break;
                case 'V': v = 5; break;
                case 'X': v = 10; break;
                default: v = 0;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** The nearest "LAST, FIRST (dates)" folder above the file, or null. */
    public static String composerFolder(String path) {
        if (path == null) return null;
        File f = new File(path).getParentFile();
        for (int i = 0; i < 4 && f != null; i++, f = f.getParentFile()) {
            if (COMPOSER_FOLDER.matcher(f.getName()).matches()) return f.getName();
        }
        return null;
    }

    /** "BACH, JOHANN SEBASTIAN (1685-1750)" -> "Johann Sebastian Bach". */
    static String folderToName(String folder) {
        Matcher m = COMPOSER_FOLDER.matcher(folder);
        if (!m.matches()) return folder;
        return m.group(2).trim() + " " + m.group(1).trim();
    }

    /** Title-cases ALL-CAPS names, keeping particles lower case. */
    static String tidyName(String name) {
        String n = name.trim().replaceAll("\\s+", " ");
        if (!n.equals(n.toUpperCase(Locale.ROOT))) return n;
        StringBuilder sb = new StringBuilder();
        for (String w : n.toLowerCase(Locale.ROOT).split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            if (w.matches("von|van|de|da|des|di|du|le|la|der")) sb.append(w);
            else sb.append(capitalizeParts(w));
        }
        return sb.toString();
    }

    private static String capitalizeParts(String w) {
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : w.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : c);
            up = c == '-' || c == '.' || c == '\'';
        }
        return sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty() || s.trim().equalsIgnoreCase("<unknown>");
    }
}
