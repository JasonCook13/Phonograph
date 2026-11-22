package com.kabouzeid.gramophone.glide.audiocover;

import android.media.MediaMetadataRetriever;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AudioFileCoverFetcher implements DataFetcher<InputStream> {
    private final AudioFileCover model;

    private InputStream stream;

    public AudioFileCoverFetcher(AudioFileCover model) {

        this.model = model;
    }

    @Override
    public String getId() {
        // makes sure we never ever return null here
        return String.valueOf(model.filePath);
    }

    @Override
    public InputStream loadData(final Priority priority) throws Exception {

        stream = load(model.filePath);
        // A merged album (singles from several folders, a set with a work folder that has no art):
        // try the other tracks' folders, one per folder, before giving up.
        if (stream == null) {
            java.util.Set<String> folders = new java.util.HashSet<>();
            folders.add(String.valueOf(new java.io.File(model.filePath).getParent()));
            for (String alt : model.alternatives) {
                if (alt == null || !folders.add(String.valueOf(new java.io.File(alt).getParent()))) continue;
                stream = load(alt);
                if (stream != null) break;
                if (folders.size() > 12) break;
            }
        }
        return stream;
    }

    private static InputStream load(String path) throws Exception {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture != null) return new ByteArrayInputStream(picture);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        return AudioFileCoverUtils.fallback(path);
    }

    @Override
    public void cleanup() {
        // already cleaned up in loadData and ByteArrayInputStream will be GC'd
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignore) {
                // can't do much about it
            }
        }
    }

    @Override
    public void cancel() {
        // cannot cancel
    }
}
