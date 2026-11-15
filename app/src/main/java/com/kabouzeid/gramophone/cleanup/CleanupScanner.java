package com.kabouzeid.gramophone.cleanup;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.kabouzeid.gramophone.loader.SongLoader;
import com.kabouzeid.gramophone.model.Song;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Reads the real tags of every song on the device. Slow (touches every file): run off the UI thread. */
public final class CleanupScanner {
    private static final String TAG = "CleanupScanner";
    // Keep a strong reference: java.util.logging forgets levels of loggers that get collected.
    private static final Logger JAUDIOTAGGER_LOGGER = Logger.getLogger("org.jaudiotagger");

    public interface Progress {
        /** Called from the scanning thread. Return false to cancel. */
        boolean onProgress(int done, int total);
    }

    public static final class Result {
        public final List<FileTags> files;
        public final int unreadable;

        Result(List<FileTags> files, int unreadable) {
            this.files = files;
            this.unreadable = unreadable;
        }
    }

    private CleanupScanner() {
    }

    public static void quietLogging() {
        JAUDIOTAGGER_LOGGER.setLevel(Level.OFF);
    }

    @NonNull
    public static Result scan(@NonNull Context context, @NonNull Progress progress) {
        quietLogging();
        List<Song> songs = SongLoader.getAllSongsRaw(context);
        List<FileTags> files = new ArrayList<>(songs.size());
        int unreadable = 0;
        for (int i = 0; i < songs.size(); i++) {
            if (!progress.onProgress(i, songs.size())) break;
            Song song = songs.get(i);
            FileTags tags = read(song);
            if (tags != null) files.add(tags);
            else unreadable++;
        }
        progress.onProgress(songs.size(), songs.size());
        return new Result(files, unreadable);
    }

    private static FileTags read(Song song) {
        if (song.data == null) return null;
        File file = new File(song.data);
        if (!file.canRead()) return null;
        try {
            AudioFile audioFile = AudioFileIO.read(file);
            Tag tag = audioFile.getTag();
            if (tag == null) {
                return new FileTags(song.id, song.data, "", "", "", "", "", false);
            }
            return new FileTags(song.id, song.data,
                    get(tag, FieldKey.TITLE),
                    get(tag, FieldKey.ARTIST),
                    get(tag, FieldKey.ALBUM),
                    get(tag, FieldKey.ALBUM_ARTIST),
                    trackOnly(get(tag, FieldKey.TRACK)),
                    !tag.getArtworkList().isEmpty());
        } catch (Throwable t) {
            Log.w(TAG, "Can't read tags of " + song.data + ": " + t);
            return null;
        }
    }

    private static String get(Tag tag, FieldKey key) {
        try {
            String v = tag.getFirst(key);
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** "3/12" -> "3", "03" -> "3". */
    private static String trackOnly(String track) {
        if (track == null) return "";
        String t = track.trim();
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash).trim();
        try {
            return t.isEmpty() ? "" : String.valueOf(Integer.parseInt(t));
        } catch (NumberFormatException e) {
            return t;
        }
    }

    /** Looks for cover.jpg / folder.png / front.jpg / ... (or a folder's only image). Cached per folder. */
    public static final class FolderCovers implements CleanupPlanner.FolderCoverLookup {
        private static final String[] PREFERRED = {"cover", "folder", "front", "album", "albumart", "albumartsmall", "artwork"};
        private final Map<String, String> cache = new HashMap<>();

        @Override
        public String findCover(String folderPath) {
            if (folderPath == null || folderPath.isEmpty()) return null;
            if (cache.containsKey(folderPath)) return cache.get(folderPath);
            String found = null;
            File[] children = new File(folderPath).listFiles();
            if (children != null) {
                List<File> images = new ArrayList<>();
                for (File f : children) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (f.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) images.add(f);
                }
                outer:
                for (String preferred : PREFERRED) {
                    for (File f : images) {
                        String n = f.getName().toLowerCase(Locale.ROOT);
                        if (n.substring(0, n.lastIndexOf('.')).startsWith(preferred)) {
                            found = f.getPath();
                            break outer;
                        }
                    }
                }
                if (found == null && images.size() == 1) found = images.get(0).getPath();
            }
            cache.put(folderPath, found);
            return found;
        }
    }
}
