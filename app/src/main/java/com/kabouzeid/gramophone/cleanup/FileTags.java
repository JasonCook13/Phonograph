package com.kabouzeid.gramophone.cleanup;

/** The real tags of one audio file, as read from the file itself. */
public class FileTags {
    public final long id;          // MediaStore id
    public final String path;
    public final String title;
    public final String artist;
    public final String album;
    public final String albumArtist;
    public final String track;
    public final String composer;
    // Extra fields for the export / Claude round trip.
    public String year = "", genre = "", conductor = "", grouping = "";
    public long durationMs;
    public final boolean hasArtwork;

    public FileTags(long id, String path, String title, String artist, String album,
                    String albumArtist, String track, boolean hasArtwork) {
        this(id, path, title, artist, album, albumArtist, track, hasArtwork, "");
    }

    public FileTags(long id, String path, String title, String artist, String album,
                    String albumArtist, String track, boolean hasArtwork, String composer) {
        this.id = id;
        this.path = path;
        this.title = nz(title);
        this.artist = nz(artist);
        this.album = nz(album);
        this.albumArtist = nz(albumArtist);
        this.track = nz(track);
        this.hasArtwork = hasArtwork;
        this.composer = nz(composer);
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
