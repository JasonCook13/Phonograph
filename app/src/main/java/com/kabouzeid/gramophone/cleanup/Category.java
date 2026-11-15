package com.kabouzeid.gramophone.cleanup;

/** Kinds of fixes the cleanup tool can make. Each can be switched on or off. */
public enum Category {
    MISSING_INFO("Fill in missing artist/album from folders"),
    TITLES("Turn file names into proper titles"),
    FEATURED("Move \"feat.\" artists into the title"),
    SPELLING("Use one spelling per artist and album"),
    ALBUM_ARTIST("Set album artist so albums stay together"),
    TRACK_NUMBERS("Add missing track numbers from file names"),
    ARTWORK("Add missing album art from the album's other tracks or folder");

    public final String label;

    Category(String label) {
        this.label = label;
    }
}
