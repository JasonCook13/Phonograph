package com.kabouzeid.gramophone.classical;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.kabouzeid.gramophone.model.Song;

import java.util.ArrayList;
import java.util.List;

/** Creates "<Composer>: Works in Order of Composition" playlists from the songs you have. */
public final class ComposerPlaylists {

    private ComposerPlaylists() {
    }

    public static String playlistName(ComposerLibrary.Composer composer) {
        return composer.name + ": Works in Order of Composition";
    }

    /** The composer's catalogued works in composition order, one recording each. */
    public static List<Song> songs(ComposerLibrary.Composer composer) {
        List<ComposerLibrary.Work> works = new ArrayList<>(composer.works);
        ComposerLibrary.sortWorks(works, ComposerLibrary.Sort.COMPOSITION);
        List<ComposerLibrary.Work> catalogued = new ArrayList<>();
        for (ComposerLibrary.Work w : works) if (w.chrono != null) catalogued.add(w);
        return ComposerLibrary.tracksInOrder(catalogued);
    }

    /**
     * Creates or replaces the playlist. Blocking, no UI: call off the main thread.
     * @return number of songs in the playlist, or -1 if it couldn't be written.
     */
    public static int write(@NonNull Context context, @NonNull ComposerLibrary.Composer composer) {
        return write(context, playlistName(composer), songs(composer));
    }

    /** Creates or replaces the playlist {@code name} with {@code songs}, in order. Blocking. */
    public static int write(@NonNull Context context, @NonNull String name, @NonNull List<Song> songs) {
        if (songs.isEmpty()) return 0;
        ContentResolver resolver = context.getContentResolver();
        try {
            long id = findOrCreate(resolver, name);
            if (id < 0) return -1;
            Uri members = MediaStore.Audio.Playlists.Members.getContentUri("external", id);
            resolver.delete(members, null, null);
            int inserted = 0;
            for (int offset = 0; offset < songs.size(); offset += 500) {
                int len = Math.min(500, songs.size() - offset);
                ContentValues[] values = new ContentValues[len];
                for (int i = 0; i < len; i++) {
                    values[i] = new ContentValues();
                    values[i].put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, offset + i);
                    values[i].put(MediaStore.Audio.Playlists.Members.AUDIO_ID, songs.get(offset + i).id);
                }
                inserted += resolver.bulkInsert(members, values);
            }
            resolver.notifyChange(members, null);
            return inserted;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long findOrCreate(ContentResolver resolver, String name) {
        Uri uri = MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI;
        try (Cursor c = resolver.query(uri, new String[]{MediaStore.Audio.Playlists._ID},
                MediaStore.Audio.PlaylistsColumns.NAME + "=?", new String[]{name}, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        ContentValues values = new ContentValues(1);
        values.put(MediaStore.Audio.PlaylistsColumns.NAME, name);
        Uri created = resolver.insert(uri, values);
        if (created == null) return -1;
        resolver.notifyChange(created, null);
        return Long.parseLong(created.getLastPathSegment());
    }
}
