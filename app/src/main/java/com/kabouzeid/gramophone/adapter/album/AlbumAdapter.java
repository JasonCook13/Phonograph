package com.kabouzeid.gramophone.adapter.album;

import android.graphics.drawable.Drawable;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.appcompat.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.bumptech.glide.Glide;
import com.kabouzeid.appthemehelper.util.ColorUtil;
import com.kabouzeid.appthemehelper.util.MaterialValueHelper;
import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.adapter.base.AbsMultiSelectAdapter;
import com.kabouzeid.gramophone.adapter.base.MediaEntryViewHolder;
import com.kabouzeid.gramophone.glide.PhonographColoredTarget;
import com.kabouzeid.gramophone.glide.SongGlideRequest;
import com.kabouzeid.gramophone.helper.SortOrder;
import com.kabouzeid.gramophone.helper.menu.SongsMenuHelper;
import com.kabouzeid.gramophone.interfaces.CabHolder;
import com.kabouzeid.gramophone.model.Album;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.util.MusicUtil;
import com.kabouzeid.gramophone.util.NavigationUtil;
import com.kabouzeid.gramophone.util.PreferenceUtil;

import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
public class AlbumAdapter extends AbsMultiSelectAdapter<AlbumAdapter.ViewHolder, Album> implements FastScrollRecyclerView.SectionedAdapter {

    protected final AppCompatActivity activity;
    protected List<Album> dataSet;

    protected int itemLayoutRes;

    protected boolean usePalette = false;

    public AlbumAdapter(@NonNull AppCompatActivity activity, List<Album> dataSet, @LayoutRes int itemLayoutRes, boolean usePalette, @Nullable CabHolder cabHolder) {
        super(activity, cabHolder, R.menu.menu_media_selection);
        this.activity = activity;
        this.dataSet = dataSet;
        rebuildRows();
        this.itemLayoutRes = itemLayoutRes;
        this.usePalette = usePalette;

        setHasStableIds(true);
    }

    public void usePalette(boolean usePalette) {
        this.usePalette = usePalette;
        notifyDataSetChanged();
    }

    public void swapDataSet(List<Album> dataSet) {
        this.dataSet = dataSet;
        rebuildRows();
        notifyDataSetChanged();
    }

    public List<Album> getDataSet() {
        return dataSet;
    }

    // ---------------------------------------------------------------------------------------
    // Genre headers: when sorted by genre, each genre starts a new row under a thin line and its name
    // ---------------------------------------------------------------------------------------

    private static final int VIEW_TYPE_GENRE_HEADER = 0x6E7E;
    /** Albums and, in genre order, genre names (String) before each genre. */
    private final List<Object> rows = new ArrayList<>();

    private void rebuildRows() {
        rows.clear();
        boolean headers = !(this instanceof HorizontalAlbumAdapter)
                && SortOrder.AlbumSortOrder.ALBUM_GENRE.equals(PreferenceUtil.getInstance(activity).getAlbumSortOrder());
        if (headers) {
            headers = false;
            for (Album a : dataSet) if (a.genre != null) { headers = true; break; }
        }
        String last = null;
        boolean first = true;
        for (Album a : dataSet) {
            if (headers) {
                String g = a.genre == null ? "Other" : a.genre;
                if (first || !g.equals(last)) rows.add(g);
                last = g;
                first = false;
            }
            rows.add(a);
        }
    }

    /** True for a genre-name row (spans the whole grid width). */
    public boolean isHeader(int position) {
        return position >= 0 && position < rows.size() && rows.get(position) instanceof String;
    }

    @Nullable
    protected Album albumAt(int position) {
        if (position < 0 || position >= rows.size()) return null;
        Object o = rows.get(position);
        return o instanceof Album ? (Album) o : null;
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? VIEW_TYPE_GENRE_HEADER : super.getItemViewType(position);
    }

    private ViewHolder createGenreHeader() {
        android.widget.LinearLayout l = new android.widget.LinearLayout(activity);
        l.setOrientation(android.widget.LinearLayout.VERTICAL);
        int dp = Math.round(activity.getResources().getDisplayMetrics().density);
        l.setPadding(16 * dp, 12 * dp, 16 * dp, 4 * dp);
        l.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(activity);
        line.setBackgroundColor(com.kabouzeid.appthemehelper.util.ATHUtil.resolveColor(activity, R.attr.dividerColor));
        l.addView(line, new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp)));
        android.widget.TextView t = new android.widget.TextView(activity);
        t.setTag("genre");
        t.setTextAppearance(activity, R.style.TextAppearance_AppCompat_Subhead);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(com.kabouzeid.gramophone.util.BrandColors.goldText(activity));
        t.setPadding(0, 8 * dp, 0, 4 * dp);
        l.addView(t);
        ViewHolder h = new ViewHolder(l);
        l.setOnClickListener(null);
        l.setOnLongClickListener(null);
        l.setClickable(false);
        return h;
    }

    @Override
    @NonNull
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_GENRE_HEADER) return createGenreHeader();
        View view = LayoutInflater.from(activity).inflate(itemLayoutRes, parent, false);
        return createViewHolder(view, viewType);
    }

    protected ViewHolder createViewHolder(View view, int viewType) {
        return new ViewHolder(view);
    }

    protected String getAlbumTitle(Album album) {
        return album.getTitle();
    }

    protected String getAlbumText(Album album) {
        return MusicUtil.buildInfoString(
            album.getArtistName(),
            MusicUtil.getSongCountString(activity, album.songs.size())
        );
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        if (isHeader(position)) {
            ((android.widget.TextView) holder.itemView.findViewWithTag("genre")).setText((String) rows.get(position));
            return;
        }
        final Album album = albumAt(position);

        final boolean isChecked = isChecked(album);
        holder.itemView.setActivated(isChecked);

        if (holder.getAdapterPosition() == getItemCount() - 1) {
            if (holder.shortSeparator != null) {
                holder.shortSeparator.setVisibility(View.GONE);
            }
        } else {
            if (holder.shortSeparator != null) {
                holder.shortSeparator.setVisibility(View.VISIBLE);
            }
        }

        if (holder.title != null) {
            holder.title.setText(getAlbumTitle(album));
        }
        if (holder.text != null) {
            holder.text.setText(getAlbumText(album));
        }

        loadAlbumCover(album, holder);
    }

    protected void setColors(int color, ViewHolder holder) {
        if (holder.paletteColorContainer != null) {
            holder.paletteColorContainer.setBackgroundColor(color);
            if (holder.title != null) {
                holder.title.setTextColor(MaterialValueHelper.getPrimaryTextColor(activity, ColorUtil.isColorLight(color)));
            }
            if (holder.text != null) {
                holder.text.setTextColor(MaterialValueHelper.getSecondaryTextColor(activity, ColorUtil.isColorLight(color)));
            }
        }
    }

    protected void loadAlbumCover(Album album, final ViewHolder holder) {
        if (holder.image == null) return;

        SongGlideRequest.Builder.from(Glide.with(activity), album.safeGetFirstSong())
                .alternatives(album.songs)
                .checkIgnoreMediaStore(activity)
                .generatePalette(activity).build()
                .into(new PhonographColoredTarget(holder.image) {
                    @Override
                    public void onLoadCleared(Drawable placeholder) {
                        super.onLoadCleared(placeholder);
                        setColors(getDefaultFooterColor(), holder);
                    }

                    @Override
                    public void onColorReady(int color) {
                        if (usePalette)
                            setColors(color, holder);
                        else
                            setColors(getDefaultFooterColor(), holder);
                    }
                });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public long getItemId(int position) {
        Album a = albumAt(position);
        return a != null ? a.getId() : -1000L - position;
    }

    @Override
    protected Album getIdentifier(int position) {
        return albumAt(position);
    }

    @Override
    protected String getName(Album album) {
        return album.getTitle();
    }

    @Override
    protected void onMultipleItemAction(@NonNull MenuItem menuItem, @NonNull List<Album> selection) {
        SongsMenuHelper.handleMenuClick(activity, getSongList(selection), menuItem.getItemId());
    }

    @NonNull
    private List<Song> getSongList(@NonNull List<Album> albums) {
        final List<Song> songs = new ArrayList<>();
        for (Album album : albums) {
            songs.addAll(album.songs);
        }
        return songs;
    }

    @NonNull
    @Override
    public String getSectionName(int position) {
        if (isHeader(position)) return MusicUtil.getSectionName((String) rows.get(position));
        if (albumAt(position) == null) return "";
        @Nullable String sectionName = null;
        switch (PreferenceUtil.getInstance(activity).getAlbumSortOrder()) {
            case SortOrder.AlbumSortOrder.ALBUM_A_Z:
            case SortOrder.AlbumSortOrder.ALBUM_Z_A:
                sectionName = albumAt(position).getTitle();
                break;
            case SortOrder.AlbumSortOrder.ALBUM_ARTIST:
                sectionName = albumAt(position).getArtistName();
                break;
            case SortOrder.AlbumSortOrder.ALBUM_YEAR:
                return MusicUtil.getYearString(albumAt(position).getYear());
            case SortOrder.AlbumSortOrder.ALBUM_GENRE:
                sectionName = albumAt(position).genre;
                break;
        }

        return MusicUtil.getSectionName(sectionName);
    }

    public class ViewHolder extends MediaEntryViewHolder {

        public ViewHolder(@NonNull final View itemView) {
            super(itemView);
            setImageTransitionName(activity.getString(R.string.transition_album_art));
            if (menu != null) {
                menu.setVisibility(View.GONE);
            }
        }

        @Override
        public void onClick(View v) {
            if (albumAt(getAdapterPosition()) == null) return;
            if (isInQuickSelectMode()) {
                toggleChecked(getAdapterPosition());
            } else {
                Pair[] albumPairs = new Pair[]{
                        Pair.create(image,
                                activity.getResources().getString(R.string.transition_album_art)
                        )};
                NavigationUtil.goToAlbum(activity, albumAt(getAdapterPosition()).getId(), albumPairs);
            }
        }

        @Override
        public boolean onLongClick(View view) {
            if (albumAt(getAdapterPosition()) == null) return false;
            toggleChecked(getAdapterPosition());
            return true;
        }
    }
}
