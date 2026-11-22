package com.kabouzeid.gramophone.glide.audiocover;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AudioFileCover {
    public final String filePath;
    /** Other files of the same album to try when the first one has no art (its folder may have none). */
    public final java.util.List<String> alternatives;

    public AudioFileCover(String filePath) {
        this(filePath, null);
    }

    public AudioFileCover(String filePath, java.util.List<String> alternatives) {
        this.filePath = filePath;
        this.alternatives = alternatives == null ? java.util.Collections.<String>emptyList() : alternatives;
    }
}
