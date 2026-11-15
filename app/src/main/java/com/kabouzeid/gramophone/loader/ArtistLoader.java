package com.kabouzeid.gramophone.loader;

import android.content.Context;
import android.provider.MediaStore.Audio.AudioColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kabouzeid.gramophone.model.Album;
import com.kabouzeid.gramophone.model.Artist;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.util.PreferenceUtil;

import com.kabouzeid.gramophone.util.LibraryCleanup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class ArtistLoader {
    public static String getSongLoaderSortOrder(Context context) {
        return PreferenceUtil.getInstance(context).getArtistSortOrder() + ", " + PreferenceUtil.getInstance(context).getArtistAlbumSortOrder() + ", " + PreferenceUtil.getInstance(context).getAlbumSongSortOrder();
    }

    @NonNull
    public static List<Artist> getAllArtists(@NonNull final Context context) {
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(
                context,
                null,
                null,
                getSongLoaderSortOrder(context))
        );
        return splitIntoArtists(AlbumLoader.splitIntoAlbums(songs));
    }

    @NonNull
    public static List<Artist> getArtists(@NonNull final Context context, String query) {
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(
                context,
                AudioColumns.ARTIST + " LIKE ?",
                new String[]{"%" + query + "%"},
                getSongLoaderSortOrder(context))
        );
        return splitIntoArtists(AlbumLoader.splitIntoAlbums(songs));
    }

    @NonNull
    public static Artist getArtist(@NonNull final Context context, long artistId) {
        if (LibraryCleanup.isEnabled()) {
            // Artists may be merged ("X feat. Y" -> "X"): find the one holding this id.
            Artist fallback = null;
            for (Artist artist : getAllArtists(context)) {
                if (artist.getId() == artistId) return artist;
                if (fallback == null) {
                    for (Song song : artist.getSongs()) {
                        if (song.artistId == artistId) {
                            fallback = artist;
                            break;
                        }
                    }
                }
            }
            return fallback != null ? fallback : new Artist();
        }
        List<Song> songs = SongLoader.getSongs(SongLoader.makeSongCursor(
                context,
                AudioColumns.ARTIST_ID + "=?",
                new String[]{String.valueOf(artistId)},
                getSongLoaderSortOrder(context))
        );
        return new Artist(AlbumLoader.splitIntoAlbums(songs));
    }

    @NonNull
    public static List<Artist> splitIntoArtists(@Nullable final List<Album> albums) {
        List<Artist> artists = new ArrayList<>();
        if (albums != null && LibraryCleanup.isEnabled()) {
            Map<String, Artist> byKey = new LinkedHashMap<>();
            for (Album album : albums) {
                String key = LibraryCleanup.artistKey(album.getArtistName());
                Artist artist = byKey.get(key);
                if (artist == null) {
                    artist = new Artist();
                    byKey.put(key, artist);
                }
                artist.albums.add(album);
            }
            artists.addAll(byKey.values());
        } else if (albums != null) {
            for (Album album : albums) {
                getOrCreateArtist(artists, album.getArtistId()).albums.add(album);
            }
        }
        return artists;
    }

    private static Artist getOrCreateArtist(List<Artist> artists, long artistId) {
        for (Artist artist : artists) {
            if (!artist.albums.isEmpty() && !artist.albums.get(0).songs.isEmpty() && artist.albums.get(0).songs.get(0).artistId == artistId) {
                return artist;
            }
        }
        Artist artist = new Artist();
        artists.add(artist);
        return artist;
    }
}
