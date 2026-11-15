package com.kabouzeid.gramophone.cleanup;

/** One proposed tag change to one file. */
public class Change {
    public enum Field {TITLE, ARTIST, ALBUM, ALBUM_ARTIST, TRACK, ARTWORK}

    public final FileTags file;
    public final Field field;
    public final String oldValue;
    /** For ARTWORK: path of the image file or audio file to take the picture from. */
    public final String newValue;
    public final Category category;

    public Change(FileTags file, Field field, String oldValue, String newValue, Category category) {
        this.file = file;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.category = category;
    }
}
