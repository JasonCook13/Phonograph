package com.kabouzeid.gramophone.classical;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds catalogue numbers in text ("BWV 1046", "K. 525", "D 810", "Op. 59 No. 3", "Hob. XVI:52")
 * and turns them into comparable keys ("bwv 1046", "k 525", "d 810", "op 59", "op 59 3", ...).
 */
public final class Catalogue {

    private static final class Sys {
        final String key;
        final Pattern pattern;

        Sys(String key, String regex) {
            this.key = key;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        }
    }

    private static final String END = "(?![\\p{L}])";
    private static final String NUM = "(\\d{1,4}[a-z]?)" + END;

    private static final Sys[] SYSTEMS = {
            new Sys("bwv", "\\bBWV\\.?\\s*(?:Anh\\.?\\s*)?" + NUM),
            new Sys("buxwv", "\\bBuxWV\\.?\\s*" + NUM),
            new Sys("hwv", "\\bHWV\\.?\\s*" + NUM),
            new Sys("twv", "\\bT(?:V)?WV\\.?\\s*(\\d{1,2}\\s*:\\s*[\\w]+)"),
            new Sys("swv", "\\bSWV\\.?\\s*" + NUM),
            new Sys("wab", "\\bWAB\\.?\\s*" + NUM),
            new Sys("mwv", "\\bMWV\\.?\\s*([A-Z]{1,2}\\s*\\d{1,3})"),
            new Sys("hob", "\\bHob\\.?\\s*([IVXL]+[a-z]?(?:\\s*:\\s*|\\s+)[A-Za-z]?\\d{1,3}[a-z]?)"),
            new Sys("rv", "\\bRV\\.?\\s*" + NUM),
            new Sys("lwv", "\\bLWV\\.?\\s*" + NUM),
            new Sys("woo", "\\bWoO\\.?\\s*" + NUM),
            new Sys("wq", "\\bWq\\.?\\s*(\\d{1,3}(?:[/.]\\d{1,3})?)"),
            new Sys("trv", "\\bTrV\\.?\\s*" + NUM),
            new Sys("fp", "\\bFP\\.?\\s*" + NUM),
            new Sys("rct", "\\bRCT\\.?\\s*" + NUM),
            new Sys("sv", "\\bSV\\.?\\s*" + NUM),
            new Sys("jw", "\\bJW\\.?\\s*([IVX]+\\s*/\\s*\\d{1,3})"),
            new Sys("js", "\\bJS\\.?\\s*" + NUM),
            new Sys("sz", "\\bSz\\.?\\s*" + NUM),
            new Sys("bb", "\\bBB\\.?\\s*" + NUM),
            new Sys("th", "\\bTH\\.?\\s*" + NUM),
            new Sys("z", "\\bZ\\.?\\s*" + NUM),
            new Sys("k", "\\bK(?:V|k)?\\.?\\s*" + NUM),
            new Sys("d", "(?<!\\bin\\s)\\bD\\.?\\s*" + NUM),
            new Sys("b", "(?<!\\bin\\s)\\bB\\.\\s*" + NUM),
            new Sys("l", "\\bL\\.\\s*" + NUM),
            new Sys("m", "\\bM\\.\\s*" + NUM),
            new Sys("s", "\\bS\\.\\s*" + NUM),
            new Sys("h", "\\bH\\.\\s*" + NUM),
    };

    private static final Pattern OPUS = Pattern.compile(
            "\\bOp(?:us|\\.)?\\s*(\\d{1,3}[a-z]?)(?:\\s*,?\\s*(?:No\\.?|Nr\\.?|n\\.|#)\\s*(\\d{1,3}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OP_SLASH = Pattern.compile("\\bOp(?:us|\\.)?\\s*(\\d{1,3}[a-z]?)\\s*/\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);

    private Catalogue() {
    }

    /** All catalogue keys found in {@code text}, most specific first. */
    public static List<String> keys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return new ArrayList<>(keys);
        for (Sys sys : SYSTEMS) {
            Matcher m = sys.pattern.matcher(text);
            while (m.find()) {
                keys.add(sys.key + " " + clean(m.group(1)));
            }
        }
        Matcher m = OP_SLASH.matcher(text);
        while (m.find()) {
            keys.add("op " + clean(m.group(1)) + " " + Integer.parseInt(m.group(2)));
            keys.add("op " + clean(m.group(1)));
        }
        m = OPUS.matcher(text);
        while (m.find()) {
            if (m.group(2) != null) keys.add("op " + clean(m.group(1)) + " " + Integer.parseInt(m.group(2)));
            keys.add("op " + clean(m.group(1)));
        }
        return new ArrayList<>(keys);
    }

    /** "0012" -> "12", "XVI : 52" / "XVI 52" -> "xvi:52". */
    private static String clean(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim().replaceAll("^([ivxl]+[a-z]?)\\s*(?::\\s*|\\s+)", "$1:").replaceAll("\\s+", "");
        t = t.replaceFirst("^0+(?=\\d)", "");
        return t;
    }

    /** True if {@code text} carries a catalogue number of a kind that only classical music has. */
    public static boolean looksClassical(String text) {
        if (text == null) return false;
        for (String k : keys(text)) {
            if (!k.startsWith("op ") && !k.startsWith("k ") && !k.startsWith("d ") && !k.startsWith("b ")
                    && !k.startsWith("l ") && !k.startsWith("m ") && !k.startsWith("s ") && !k.startsWith("h ")
                    && !k.startsWith("z ")) return true;
        }
        return OPUS.matcher(text).find();
    }
}
