package com.kabouzeid.gramophone.ui.activities;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import com.kabouzeid.gramophone.model.Album;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.afollestad.materialcab.MaterialCab;
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils;
import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.adapter.song.SongAdapter;
import com.kabouzeid.gramophone.helper.MusicPlayerRemote;
import com.kabouzeid.gramophone.interfaces.CabHolder;
import com.kabouzeid.gramophone.interfaces.LoaderIds;
import com.kabouzeid.gramophone.loader.GenreLoader;
import com.kabouzeid.gramophone.misc.WrappedAsyncTaskLoader;
import com.kabouzeid.gramophone.model.Genre;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.ui.activities.base.AbsSlidingMusicPanelActivity;
import com.kabouzeid.gramophone.util.PhonographColorUtil;
import com.kabouzeid.gramophone.util.ViewUtil;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;

/** A genre as shelves: each artist (composers, for Soundtracks) with their albums in release order;
 *  tap a cover for its tracks. Soundtracks can switch to Films A–Z. Shuffle plays the whole genre. */
public class GenreDetailActivity extends AbsSlidingMusicPanelActivity implements CabHolder, LoaderManager.LoaderCallbacks<List<Song>> {

    private static final int LOADER_ID = LoaderIds.GENRE_DETAIL_ACTIVITY;

    public static final String EXTRA_GENRE = "extra_genre";

    @BindView(R.id.recycler_view)
    RecyclerView recyclerView;
    @BindView(R.id.toolbar)
    Toolbar toolbar;
    @BindView(android.R.id.empty)
    TextView empty;

    private Genre genre;

    private MaterialCab cab;
    private ShelfAdapter adapter;
    private com.kabouzeid.gramophone.adapter.album.AlbumAdapter filmsAdapter;
    private List<Song> songs = new ArrayList<>();
    private List<Album> albums = new ArrayList<>();
    private boolean soundtracks, filmsAZ;
    private TextView summary;

    private RecyclerView.Adapter wrappedAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDrawUnderStatusbar();
        ButterKnife.bind(this);

        setStatusbarColorAuto();
        setNavigationbarColorAuto();
        setTaskDescriptionColorAuto();

        genre = getIntent().getExtras().getParcelable(EXTRA_GENRE);

        setUpRecyclerView();

        setUpToolBar();

        getSupportLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @Override
    protected View createContentView() {
        return wrapSlidingMusicPanel(R.layout.activity_genre_detail);
    }

    private void setUpRecyclerView() {
        ViewUtil.setUpFastScrollRecyclerViewColor(this, ((FastScrollRecyclerView) recyclerView), ThemeStore.accentColor(this));
        soundtracks = genre.name != null && genre.name.trim().equalsIgnoreCase("Soundtracks");
        adapter = new ShelfAdapter();
        showShelves();
    }

    private void setUpToolBar() {
        toolbar.setBackgroundColor(ThemeStore.primaryColor(this));
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setTitle(genre.name);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_genre_detail, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_shuffle_genre:
                MusicPlayerRemote.openAndShuffleQueue(songs, true);
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @NonNull
    @Override
    public MaterialCab openCab(final int menu, final MaterialCab.Callback callback) {
        if (cab != null && cab.isActive()) cab.finish();
        cab = new MaterialCab(this, R.id.cab_stub)
                .setMenu(menu)
                .setCloseDrawableRes(R.drawable.ic_close_white_24dp)
                .setBackgroundColor(PhonographColorUtil.shiftBackgroundColorForLightText(ThemeStore.primaryColor(this)))
                .start(callback);
        return cab;
    }

    @Override
    public void onBackPressed() {
        if (cab != null && cab.isActive()) cab.finish();
        else {
            recyclerView.stopScroll();
            super.onBackPressed();
        }
    }

    @Override
    public void onMediaStoreChanged() {
        super.onMediaStoreChanged();
        getSupportLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    private void checkIsEmpty() {
        empty.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showShelves() {
        filmsAZ = false;
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    /** Soundtracks: every film as a cover grid, A–Z. */
    private void showFilms() {
        filmsAZ = true;
        int columns = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 4 : 2;
        final androidx.recyclerview.widget.GridLayoutManager lm = new androidx.recyclerview.widget.GridLayoutManager(this, columns);
        recyclerView.setLayoutManager(lm);
        List<Album> byTitle = new ArrayList<>(albums);
        java.util.Collections.sort(byTitle, (a, b) -> sortKey(a.getTitle()).compareTo(sortKey(b.getTitle())));
        filmsAdapter = new com.kabouzeid.gramophone.adapter.album.AlbumAdapter(this, byTitle, R.layout.item_grid, false, this);
        recyclerView.setAdapter(filmsAdapter);
    }

    private static String sortKey(String s) {
        if (s == null) return "";
        String l = s.trim().toLowerCase(java.util.Locale.ROOT);
        for (String a : new String[]{"the ", "a ", "an "}) if (l.startsWith(a)) l = l.substring(a.length());
        return java.text.Normalizer.normalize(l, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Who an album belongs to on this page: the composer for a soundtrack
     *  (.../Soundtracks/<Film>/<Composer>/track), otherwise the album artist. */
    private String ownerOf(Album album) {
        if (soundtracks) {
            String path = album.safeGetFirstSong().data;
            if (path != null) {
                String[] seg = path.replace('\\', '/').split("/");
                for (int i = 0; i + 3 < seg.length; i++) {
                    if (seg[i].equalsIgnoreCase("Soundtracks")) return seg[i + 2];
                }
            }
        }
        String a = album.getArtistName();
        return a == null || a.trim().isEmpty() ? "Unknown artist" : a.trim();
    }

    private static final class Shelf {
        final String owner;
        final List<Album> albums = new ArrayList<>();

        Shelf(String owner) {
            this.owner = owner;
        }
    }

    private final List<Shelf> shelves = new ArrayList<>();

    private void buildShelves() {
        java.util.LinkedHashMap<String, Shelf> by = new java.util.LinkedHashMap<>();
        for (Album a : albums) {
            String o = ownerOf(a);
            String k = sortKey(o);
            Shelf s = by.get(k);
            if (s == null) by.put(k, s = new Shelf(o));
            s.albums.add(a);
        }
        shelves.clear();
        shelves.addAll(by.values());
        for (Shelf s : shelves) {
            java.util.Collections.sort(s.albums, (a, b) -> {
                int ya = a.getYear() > 0 ? a.getYear() : 99999, yb = b.getYear() > 0 ? b.getYear() : 99999;
                return ya != yb ? ya - yb : sortKey(a.getTitle()).compareTo(sortKey(b.getTitle()));
            });
        }
        java.util.Collections.sort(shelves, (a, b) -> {
            boolean va = a.owner.toLowerCase().startsWith("various"), vb = b.owner.toLowerCase().startsWith("various");
            if (va != vb) return va ? 1 : -1;
            return sortKey(a.owner).compareTo(sortKey(b.owner));
        });
    }

    /** Row 0: summary (and the Composers / Films A–Z switch); then one shelf per artist. */
    private class ShelfAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
            implements FastScrollRecyclerView.SectionedAdapter {
        @Override
        public int getItemCount() {
            return shelves.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @NonNull
        @Override
        public String getSectionName(int position) {
            return position == 0 ? "" : com.kabouzeid.gramophone.util.MusicUtil.getSectionName(sortKey(shelves.get(position - 1).owner));
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return viewType == 0 ? new TopHolder() : new ShelfHolder();
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof TopHolder) ((TopHolder) holder).bind();
            else ((ShelfHolder) holder).bind(shelves.get(position - 1));
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private class TopHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final LinearLayout toggle;

        TopHolder() {
            super(new LinearLayout(GenreDetailActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(16), dp(10), dp(16), dp(4));
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            text = new TextView(GenreDetailActivity.this);
            text.setTextAppearance(GenreDetailActivity.this, R.style.TextAppearance_AppCompat_Body1);
            l.addView(text);
            toggle = new LinearLayout(GenreDetailActivity.this);
            toggle.setOrientation(LinearLayout.HORIZONTAL);
            l.addView(toggle);
        }

        void bind() {
            int n = shelves.size();
            text.setText(n + (soundtracks ? (n == 1 ? " composer · " : " composers · ") : (n == 1 ? " artist · " : " artists · "))
                    + albums.size() + (soundtracks ? (albums.size() == 1 ? " film" : " films") : (albums.size() == 1 ? " album" : " albums")));
            toggle.removeAllViews();
            toggle.setVisibility(soundtracks ? View.VISIBLE : View.GONE);
            if (!soundtracks) return;
            int gold = com.kabouzeid.gramophone.util.BrandColors.goldText(GenreDetailActivity.this);
            String[] labels = {"Composers", "Films A–Z"};
            for (int i = 0; i < 2; i++) {
                if (i == 1) {
                    TextView dot = new TextView(GenreDetailActivity.this);
                    dot.setText("·");
                    toggle.addView(dot);
                }
                TextView t = new TextView(GenreDetailActivity.this);
                t.setText(labels[i]);
                t.setPadding(dp(8), dp(10), dp(8), dp(10));
                boolean active = (i == 1) == filmsAZ;
                if (active) {
                    t.setTextColor(gold);
                    t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                } else {
                    t.setAlpha(0.7f);
                    final boolean films = i == 1;
                    t.setOnClickListener(v -> {
                        if (films) showFilms();
                        else showShelves();
                    });
                }
                toggle.addView(t);
            }
        }
    }

    private class ShelfHolder extends RecyclerView.ViewHolder {
        final TextView name, count;
        final RecyclerView row;

        ShelfHolder() {
            super(new LinearLayout(GenreDetailActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View line = new View(GenreDetailActivity.this);
            line.setBackgroundColor(com.kabouzeid.appthemehelper.util.ATHUtil.resolveColor(GenreDetailActivity.this, R.attr.dividerColor));
            l.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
            LinearLayout head = new LinearLayout(GenreDetailActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding(dp(16), dp(10), dp(16), dp(2));
            name = new TextView(GenreDetailActivity.this);
            name.setTextAppearance(GenreDetailActivity.this, R.style.TextAppearance_AppCompat_Subhead);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(com.kabouzeid.gramophone.util.BrandColors.goldText(GenreDetailActivity.this));
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            count = new TextView(GenreDetailActivity.this);
            count.setTextAppearance(GenreDetailActivity.this, R.style.TextAppearance_AppCompat_Caption);
            head.addView(count);
            l.addView(head);
            row = new RecyclerView(GenreDetailActivity.this);
            row.setLayoutManager(new LinearLayoutManager(GenreDetailActivity.this, LinearLayoutManager.HORIZONTAL, false));
            row.setNestedScrollingEnabled(false);
            l.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(Shelf s) {
            name.setText(s.owner);
            count.setText(s.albums.size() + " ›");
            row.setAdapter(new com.kabouzeid.gramophone.adapter.album.HorizontalAlbumAdapter(GenreDetailActivity.this, s.albums, false, GenreDetailActivity.this));
            View.OnClickListener open = v -> {
                Album first = s.albums.get(0);
                if (soundtracks || s.owner.toLowerCase().startsWith("various")) return;
                com.kabouzeid.gramophone.util.NavigationUtil.goToArtist(GenreDetailActivity.this, first.getArtistId());
            };
            name.setOnClickListener(open);
            count.setOnClickListener(open);
        }
    }

    @Override
    protected void onDestroy() {
        if (recyclerView != null) {
            recyclerView.setAdapter(null);
            recyclerView = null;
        }

        if (wrappedAdapter != null) {
            WrapperAdapterUtils.releaseAll(wrappedAdapter);
            wrappedAdapter = null;
        }
        adapter = null;

        super.onDestroy();
    }

    @Override
    @NonNull
    public Loader<List<Song>> onCreateLoader(int id, Bundle args) {
        return new GenreDetailActivity.AsyncGenreSongLoader(this, genre);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<Song>> loader, List<Song> data) {
        songs = data;
        albums = com.kabouzeid.gramophone.loader.AlbumLoader.splitIntoAlbums(data);
        buildShelves();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (filmsAZ) showFilms();
        checkIsEmpty();
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<Song>> loader) {
        songs = new ArrayList<>();
        albums = new ArrayList<>();
        shelves.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private static class AsyncGenreSongLoader extends WrappedAsyncTaskLoader<List<Song>> {
        private final Genre genre;

        public AsyncGenreSongLoader(Context context, Genre genre) {
            super(context);
            this.genre = genre;
        }

        @Override
        public List<Song> loadInBackground() {
            return GenreLoader.getSongs(getContext(), genre.id);
        }
    }
}
