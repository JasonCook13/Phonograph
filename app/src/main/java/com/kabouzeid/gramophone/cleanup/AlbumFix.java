package com.kabouzeid.gramophone.cleanup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All proposed changes for one album, shown as one row in the cleanup screen. */
public class AlbumFix {
    public enum Status {PENDING, SKIPPED, FIXING, FIXED, FAILED}

    public final String key;
    public final String title;
    public final String artist;
    public final int fileCount;
    public final List<Change> changes = new ArrayList<>();
    public Status status = Status.PENDING;
    public String message;

    public AlbumFix(String key, String title, String artist, int fileCount) {
        this.key = key;
        this.title = title;
        this.artist = artist;
        this.fileCount = fileCount;
    }

    public Set<FileTags> files() {
        Set<FileTags> files = new LinkedHashSet<>();
        for (Change c : changes) files.add(c.file);
        return files;
    }
}
