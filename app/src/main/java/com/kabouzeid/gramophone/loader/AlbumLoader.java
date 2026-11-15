package com.kabouzeid.gramophone.loader;

import android.content.Context;
import android.provider.MediaStore.Audio.AudioColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kabouzeid.gramophone.model.Album;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.util.PreferenceUtil;

import com.kabouzeid.gramophone.util.LibraryCleanup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AlbumLoader {

    public static String getSongLoaderSortOrder(Context context) {
        return PreferenceUtil.getInstance(context).getAlbumSortOrder() + ", " + PreferenceUtil.getInstance(context).getAlbumSongSortOrder();
    }

    @NonNull
    public static List<Album> getAllAlbums(@NonNull final Context context) {
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(
                context,
                null,
                null,
                getSongLoaderSortOrder(context))
        );
        return splitIntoAlbums(songs);
    }

    @NonNull
    public static List<Album> getAlbums(@NonNull final Context context, String query) {
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(
                context,
                AudioColumns.ALBUM + " LIKE ?",
                new String[]{"%" + query + "%"},
                getSongLoaderSortOrder(context))
        );
        return splitIntoAlbums(songs);
    }

    @NonNull
    public static Album getAlbum(@NonNull final Context context, long albumId) {
        if (LibraryCleanup.isEnabled()) {
            // Albums may be merged from several MediaStore albums: find the one holding this id.
            Album fallback = null;
            for (Album album : getAllAlbums(context)) {
                if (album.getId() == albumId) return album;
                if (fallback == null) {
                    for (Song song : album.songs) {
                        if (song.albumId == albumId) {
                            fallback = album;
                            break;
                        }
                    }
                }
            }
            return fallback != null ? fallback : new Album();
        }
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(context, AudioColumns.ALBUM_ID + "=?", new String[]{String.valueOf(albumId)}, getSongLoaderSortOrder(context)));
        Album album = new Album(songs);
        sortSongsByTrackNumber(album);
        return album;
    }

    @NonNull
    public static List<Album> splitIntoAlbums(@Nullable final List<Song> songs) {
        List<Album> albums = new ArrayList<>();
        if (songs != null && LibraryCleanup.isEnabled()) {
            Map<String, Album> byKey = new LinkedHashMap<>();
            for (Song song : songs) {
                String key = LibraryCleanup.albumKey(song);
                Album album = byKey.get(key);
                if (album == null) {
                    album = new Album();
                    byKey.put(key, album);
                }
                album.songs.add(song);
            }
            albums.addAll(byKey.values());
        } else if (songs != null) {
            for (Song song : songs) {
                getOrCreateAlbum(albums, song.albumId).songs.add(song);
            }
        }
        for (Album album : albums) {
            sortSongsByTrackNumber(album);
        }
        return albums;
    }

    private static Album getOrCreateAlbum(List<Album> albums, long albumId) {
        for (Album album : albums) {
            if (!album.songs.isEmpty() && album.songs.get(0).albumId == albumId) {
                return album;
            }
        }
        Album album = new Album();
        albums.add(album);
        return album;
    }

    private static void sortSongsByTrackNumber(Album album) {
        // Track number (includes disc * 1000), then file name for untagged tracks.
        Collections.sort(album.songs, (o1, o2) -> {
            if (o1.trackNumber != o2.trackNumber) return o1.trackNumber - o2.trackNumber;
            String d1 = o1.data == null ? "" : o1.data;
            String d2 = o2.data == null ? "" : o2.data;
            return d1.compareToIgnoreCase(d2);
        });
    }
}
