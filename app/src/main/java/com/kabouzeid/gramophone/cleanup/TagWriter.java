package com.kabouzeid.gramophone.cleanup;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes tag changes into audio files.
 *
 * First tries to edit the file in place. If Android's storage rules refuse that, it edits a
 * temporary copy and writes it back through MediaStore, which may first need the user's
 * permission ({@link Outcome#NEEDS_PERMISSION}; ask with MediaStore.createWriteRequest, then retry).
 */
public final class TagWriter {
    private static final String TAG = "TagWriter";

    public enum Outcome {OK, NEEDS_PERMISSION, FAILED}

    public static final class Result {
        public final Outcome outcome;
        public final String error;

        Result(Outcome outcome, String error) {
            this.outcome = outcome;
            this.error = error;
        }
    }

    private final Context context;
    private final Map<String, Artwork> artworkCache = new HashMap<>();
    private final File logFile;

    public TagWriter(@NonNull Context context) {
        this.context = context.getApplicationContext();
        File dir = this.context.getExternalFilesDir(null);
        if (dir == null) dir = this.context.getFilesDir();
        logFile = new File(dir, "library-cleanup-log.csv");
        CleanupScanner.quietLogging();
    }

    public File getLogFile() {
        return logFile;
    }

    public static Uri contentUri(long songId) {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId);
    }

    /** Applies all {@code changes} (which must all belong to {@code file}). Blocking. */
    @NonNull
    public Result write(@NonNull FileTags file, @NonNull List<Change> changes) {
        if (changes.isEmpty()) return new Result(Outcome.OK, null);
        File target = new File(file.path);
        Exception directError;
        try {
            AudioFile audioFile = AudioFileIO.read(target);
            apply(audioFile, changes);
            audioFile.commit();
            log(file, changes);
            return new Result(Outcome.OK, null);
        } catch (Exception e) {
            directError = e;
            Log.w(TAG, "In-place write failed for " + file.path + ", trying MediaStore: " + e);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new Result(Outcome.FAILED, message(directError));
        }

        File temp = null;
        try {
            temp = copyToCache(target);
            AudioFile audioFile = AudioFileIO.read(temp);
            apply(audioFile, changes);
            audioFile.commit();
            try (InputStream in = new FileInputStream(temp);
                 OutputStream out = context.getContentResolver().openOutputStream(contentUri(file.id), "wt")) {
                if (out == null) throw new IOException("MediaStore gave no output stream");
                copy(in, out);
            }
            log(file, changes);
            return new Result(Outcome.OK, null);
        } catch (SecurityException e) {
            return new Result(Outcome.NEEDS_PERMISSION, message(e));
        } catch (Exception e) {
            Log.w(TAG, "MediaStore write failed for " + file.path, e);
            return new Result(Outcome.FAILED, message(e));
        } finally {
            if (temp != null) //noinspection ResultOfMethodCallIgnored
                temp.delete();
        }
    }

    private void apply(AudioFile audioFile, List<Change> changes) throws Exception {
        Tag tag = audioFile.getTagOrCreateAndSetDefault();
        for (Change c : changes) {
            if (c.field == Change.Field.ARTWORK) {
                Artwork art = loadArtwork(c.newValue);
                if (art != null) {
                    tag.deleteArtworkField();
                    tag.setField(art);
                }
                continue;
            }
            FieldKey key = fieldKey(c.field);
            if (c.newValue == null || c.newValue.isEmpty()) tag.deleteField(key);
            else tag.setField(key, c.newValue);
        }
    }

    private static FieldKey fieldKey(Change.Field field) {
        switch (field) {
            case TITLE: return FieldKey.TITLE;
            case ARTIST: return FieldKey.ARTIST;
            case ALBUM: return FieldKey.ALBUM;
            case ALBUM_ARTIST: return FieldKey.ALBUM_ARTIST;
            case TRACK: return FieldKey.TRACK;
            case COMPOSER: return FieldKey.COMPOSER;
            case YEAR: return FieldKey.YEAR;
            case GENRE: return FieldKey.GENRE;
            case CONDUCTOR: return FieldKey.CONDUCTOR;
            case GROUPING: return FieldKey.GROUPING;
            default: throw new IllegalArgumentException(String.valueOf(field));
        }
    }

    /** Picture from an image file, or the embedded picture of another audio file. */
    @Nullable
    private Artwork loadArtwork(String source) {
        if (artworkCache.containsKey(source)) return artworkCache.get(source);
        Artwork art = null;
        try {
            String lower = source.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                art = ArtworkFactory.createArtworkFromFile(new File(source));
            } else {
                Tag tag = AudioFileIO.read(new File(source)).getTag();
                if (tag != null) art = tag.getFirstArtwork();
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't load artwork from " + source, e);
        }
        artworkCache.put(source, art);
        return art;
    }

    private File copyToCache(File source) throws IOException {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot) : "";
        File temp = File.createTempFile("cleanup", ext, context.getCacheDir());
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(temp)) {
            copy(in, out);
        }
        return temp;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private synchronized void log(FileTags file, List<Change> changes) {
        boolean header = !logFile.exists();
        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
            if (header) w.println("time,file,field,old value,new value");
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            for (Change c : changes) {
                w.println(csv(now) + "," + csv(file.path) + "," + csv(c.field.name()) + ","
                        + csv(c.oldValue) + "," + csv(c.newValue));
            }
        } catch (IOException e) {
            Log.w(TAG, "Can't write cleanup log", e);
        }
    }

    private static String csv(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
