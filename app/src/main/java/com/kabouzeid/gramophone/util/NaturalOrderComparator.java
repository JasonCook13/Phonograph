package com.kabouzeid.gramophone.util;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Compares strings the way people read them: runs of digits are compared by numeric value,
 * so "Op. 18" sorts before "Op. 130" and "BWV 25" before "BWV 140". Letters are compared
 * case-insensitively and accent-aware (Dvořák sorts with Dvorak).
 */
public final class NaturalOrderComparator implements Comparator<String> {

    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    private final Collator collator;

    private NaturalOrderComparator() {
        collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY);
    }

    @Override
    public int compare(String a, String b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        int i = 0, j = 0;
        final int na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            final char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (si < na && a.charAt(si) == '0') si++;
                while (sj < nb && b.charAt(sj) == '0') sj++;
                int ei = si, ej = sj;
                while (ei < na && Character.isDigit(a.charAt(ei))) ei++;
                while (ej < nb && Character.isDigit(b.charAt(ej))) ej++;
                final int la = ei - si, lb = ej - sj;
                if (la != lb) return la - lb;
                for (int k = 0; k < la; k++) {
                    final int d = a.charAt(si + k) - b.charAt(sj + k);
                    if (d != 0) return d;
                }
                // equal value: fewer leading zeros first ("7" before "07")
                final int za = si - i, zb = sj - j;
                if (za != zb) return za - zb;
                i = ei;
                j = ej;
            } else {
                int ei = i, ej = j;
                while (ei < na && !Character.isDigit(a.charAt(ei))) ei++;
                while (ej < nb && !Character.isDigit(b.charAt(ej))) ej++;
                final int c = collator.compare(a.substring(i, ei), b.substring(j, ej));
                if (c != 0) return c;
                i = ei;
                j = ej;
            }
        }
        if (i < na) return 1;
        if (j < nb) return -1;
        final int c = a.compareToIgnoreCase(b);
        return c != 0 ? c : a.compareTo(b);
    }
}
