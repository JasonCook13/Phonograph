package com.kabouzeid.gramophone.ui.activities;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.util.BrandColors;
import com.kabouzeid.gramophone.classical.ComposerLibrary;
import com.kabouzeid.gramophone.classical.ComposerPlaylists;
import com.kabouzeid.gramophone.helper.MusicPlayerRemote;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.ui.activities.base.AbsMusicServiceActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composers -> works grouped by kind (quartets, trios, symphonies ...) in order of composition,
 * with "play in order" for a whole group and composition-order playlists.
 *
 * Without EXTRA_COMPOSER it lists composers; with it, it shows that composer's works.
 */
public class ComposersActivity extends AbsMusicServiceActivity {
    public static final String EXTRA_COMPOSER = "composer";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static ComposerLibrary.Sort sort = ComposerLibrary.Sort.COMPOSITION;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String composerName;
    private ComposerLibrary library;
    private ComposerLibrary.Composer composer;
    private final List<Object> rows = new ArrayList<>();      // composer list
    private final List<Page> pages = new ArrayList<>();       // a composer's tabs
    private RecyclerView list;
    private androidx.viewpager.widget.ViewPager pager;
    private com.google.android.material.tabs.TabLayout tabs;
    private PagesAdapter pagesAdapter;
    private ProgressBar progress;
    private TextView empty;
    private int accent;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        composerName = getIntent().getStringExtra(EXTRA_COMPOSER);
        accent = BrandColors.goldText(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        getLayoutInflater().inflate(R.layout.status_bar, root, true);
        Toolbar toolbar = new Toolbar(new androidx.appcompat.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AppCompat_Dark_ActionBar));
        toolbar.setBackgroundColor(ThemeStore.primaryColor(this));
        toolbar.setPopupTheme(R.style.ThemeOverlay_AppCompat_Light);
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionBarSize()));
        if (composerName != null) {
            tabs = new com.google.android.material.tabs.TabLayout(this);
            tabs.setTabMode(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE);
            tabs.setBackgroundColor(ThemeStore.primaryColor(this));
            int primary = ThemeStore.primaryColor(this);
            tabs.setTabTextColors(com.kabouzeid.appthemehelper.util.ToolbarContentTintHelper.toolbarSubtitleColor(this, primary),
                    com.kabouzeid.appthemehelper.util.ToolbarContentTintHelper.toolbarTitleColor(this, primary));
            tabs.setSelectedTabIndicatorColor(BrandColors.GOLD);
            root.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(searchBar());
        }
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (composerName != null) {
            pager = new androidx.viewpager.widget.ViewPager(this);
            pager.setId(View.generateViewId());
            pagesAdapter = new PagesAdapter();
            pager.setAdapter(pagesAdapter);
            tabs.setupWithViewPager(pager);
            content.addView(pager);
        } else {
            list = new RecyclerView(this);
            list.setLayoutManager(new LinearLayoutManager(this));
            list.setAdapter(new Adapter(rows));
            list.setClipToPadding(false);
            list.setPadding(0, 0, 0, dp(16));
            content.addView(list);
        }
        progress = new ProgressBar(this);
        content.addView(progress, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        empty = new TextView(this);
        empty.setPadding(dp(32), dp(32), dp(32), dp(32));
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        content.addView(empty);
        setContentView(root);

        setDrawUnderStatusbar();
        setStatusbarColorAuto();
        setNavigationbarColorAuto();
        setTaskDescriptionColorAuto();
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(composerName != null ? composerName : "Composers");

        load(false);
    }

    @Override
    public void onMediaStoreChanged() {
        super.onMediaStoreChanged();
        load(true);
    }

    private void load(boolean refresh) {
        progress.setVisibility(View.VISIBLE);
        final android.content.Context app = getApplicationContext();
        EXECUTOR.execute(() -> {
            ComposerLibrary lib = ComposerLibrary.get(app, refresh);
            main.post(() -> {
                if (isFinishing()) return;
                library = lib;
                progress.setVisibility(View.GONE);
                buildRows();
            });
        });
    }

    private void buildRows() {
        rows.clear();
        if (composerName != null) {
            buildPages();
            return;
        }
        {
            rows.addAll(library.composers);
            empty.setText("No classical music found.\n\nTracks show up here when they have a composer tag, sit in a "
                    + "composer folder like \"BACH, JOHANN SEBASTIAN (1685-1750)\", or start with a known composer's name.");
        }
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        //noinspection ConstantConditions
        list.getAdapter().notifyDataSetChanged();
        supportInvalidateOptionsMenu();
    }

    /** Tabs: "All works" (every work in the chosen order), then one tab per type of work. */
    private void buildPages() {
        composer = null;
        for (ComposerLibrary.Composer c : library.composers) if (c.name.equals(composerName)) composer = c;
        String keepTitle = pages.isEmpty() || pager == null ? null : pages.get(Math.min(pager.getCurrentItem(), pages.size() - 1)).title;
        pages.clear();
        if (composer != null) {
            List<ComposerLibrary.Work> works = filtered(composer.works);
            ComposerLibrary.sortWorks(works, sort);
            Page all = new Page("All works");
            all.rows.add(new Header());
            all.rows.addAll(works);
            pages.add(all);
            for (Map.Entry<String, List<ComposerLibrary.Work>> e : ComposerLibrary.byType(works).entrySet()) {
                String name = e.getKey() == null || e.getKey().isEmpty() ? "Other" : e.getKey();
                Page page = new Page(name);
                page.rows.add(new Category(name, e.getValue()));
                page.rows.addAll(e.getValue());
                pages.add(page);
            }
        }
        empty.setText(query.isEmpty() ? "Nothing found for " + composerName + "." : "No works match \"" + query + "\".");
        boolean nothing = composer == null || pages.get(0).rows.size() <= 1;
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        pagesAdapter.notifyDataSetChanged();
        if (keepTitle != null) {
            for (int i = 0; i < pages.size(); i++) if (pages.get(i).title.equals(keepTitle)) pager.setCurrentItem(i, false);
        }
        tabs.setVisibility(pages.size() > 1 ? View.VISIBLE : View.GONE);
        supportInvalidateOptionsMenu();
    }

    private static final class Page {
        final String title;
        final List<Object> rows = new ArrayList<>();

        Page(String title) {
            this.title = title;
        }
    }

    private class PagesAdapter extends androidx.viewpager.widget.PagerAdapter {
        @Override
        public int getCount() {
            return pages.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            Page p = pages.get(position);
            int n = 0;
            for (Object o : p.rows) if (o instanceof ComposerLibrary.Work) n++;
            return p.title.toUpperCase(java.util.Locale.getDefault()) + " (" + n + ")";
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            RecyclerView rv = new RecyclerView(ComposersActivity.this);
            rv.setLayoutManager(new LinearLayoutManager(ComposersActivity.this));
            rv.setAdapter(new Adapter(pages.get(position).rows));
            rv.setClipToPadding(false);
            rv.setPadding(0, 0, 0, dp(16));
            rv.setTag(pages.get(position));
            container.addView(rv);
            return rv;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;  // pages are rebuilt on every change
        }
    }

    // ---------------------------------------------------------------------------------------
    // Search: "op 10", "trio", "razumovsky", "bwv 1046"
    // ---------------------------------------------------------------------------------------

    private View searchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(4), dp(4), dp(4));
        android.widget.EditText field = new android.widget.EditText(this);
        field.setHint("Find works: op 10, trio, BWV 1046…");
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button play = new Button(this, null, R.attr.borderlessButtonStyle);
        play.setText("Play tab");
        play.setTextColor(accent);
        Button save = new Button(this, null, R.attr.borderlessButtonStyle);
        save.setText("Save");
        save.setTextColor(accent);
        bar.addView(play);
        bar.addView(save);
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                if (library != null) buildRows();
            }
        });
        play.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(visibleWorks())));
        save.setOnClickListener(v -> askPlaylistName(defaultName(query.isEmpty() ? "Works in Order of Composition" : query), visibleWorks()));
        return bar;
    }

    /** Works whose title, catalogue number or kind contain every word of the query. */
    private List<ComposerLibrary.Work> filtered(List<ComposerLibrary.Work> works) {
        List<ComposerLibrary.Work> out = new ArrayList<>();
        String[] words = com.kabouzeid.gramophone.classical.Chronology.norm(query).split(" ");
        for (ComposerLibrary.Work w : works) {
            if (query.isEmpty()) {
                out.add(w);
                continue;
            }
            String hay = " " + com.kabouzeid.gramophone.classical.Chronology.norm(w.title + " " + w.catalogue() + " " + w.category) + " ";
            boolean all = true;
            for (String word : words) {
                if (word.isEmpty()) continue;
                // whole words for numbers ("10" must not match "100"); prefixes for words ("trio" -> "trios")
                boolean hit = word.matches("\\d+") ? hay.contains(" " + word + " ") : hay.contains(" " + word);
                if (!hit) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(w);
        }
        return out;
    }

    /** Works on the tab being shown (all works on the first tab). */
    private List<ComposerLibrary.Work> visibleWorks() {
        List<ComposerLibrary.Work> works = new ArrayList<>();
        if (pages.isEmpty()) return works;
        Page page = pages.get(Math.min(pager.getCurrentItem(), pages.size() - 1));
        for (Object o : page.rows) if (o instanceof ComposerLibrary.Work) works.add((ComposerLibrary.Work) o);
        return works;
    }

    private static List<ComposerLibrary.Work> worksOf(Page page) {
        List<ComposerLibrary.Work> works = new ArrayList<>();
        for (Object o : page.rows) if (o instanceof ComposerLibrary.Work) works.add((ComposerLibrary.Work) o);
        return works;
    }

    private String defaultName(String what) {
        String shortName = composer != null && composer.chrono != null ? capitalize(composer.chrono.sortName()) : composerName;
        return shortName + ": " + what;
    }

    private static String capitalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return sb.toString();
    }

    /** Asks for a playlist name, then saves these works (one recording each, in order). */
    private void askPlaylistName(String suggested, List<ComposerLibrary.Work> works) {
        if (works.isEmpty()) return;
        final List<Song> songs = ComposerLibrary.tracksInOrder(works);
        new MaterialDialog.Builder(this)
                .title("Save as playlist")
                .content(works.size() + (works.size() == 1 ? " work, " : " works, ") + songs.size() + " tracks")
                .input("Playlist name", suggested, false, (d, input) -> {
                    final String name = input.toString().trim();
                    final android.content.Context app = getApplicationContext();
                    EXECUTOR.execute(() -> {
                        int n = ComposerPlaylists.write(app, name, songs);
                        main.post(() -> Toast.makeText(app, n > 0 ? "Saved \"" + name + "\" (" + n + " tracks)"
                                : "Couldn't save the playlist", Toast.LENGTH_LONG).show());
                    });
                })
                .positiveText("Save")
                .negativeText(android.R.string.cancel)
                .show();
    }

    // ---------------------------------------------------------------------------------------
    // Menu
    // ---------------------------------------------------------------------------------------

    private static final int MENU_SORT_COMPOSITION = 1, MENU_SORT_CATALOGUE = 2, MENU_SORT_TITLE = 3,
            MENU_PLAYLIST = 4, MENU_ALL_PLAYLISTS = 5;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (composerName != null) {
            menu.add(0, MENU_PLAYLIST, 0, "Save as composition-order playlist");
            menu.add(1, MENU_SORT_COMPOSITION, 1, "Sort: order of composition").setCheckable(true);
            menu.add(1, MENU_SORT_CATALOGUE, 2, "Sort: catalogue number").setCheckable(true);
            menu.add(1, MENU_SORT_TITLE, 3, "Sort: title").setCheckable(true);
            menu.setGroupCheckable(1, true, true);
        } else {
            menu.add(0, MENU_ALL_PLAYLISTS, 0, "Create composition-order playlists");
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(sort == ComposerLibrary.Sort.CATALOGUE ? MENU_SORT_CATALOGUE
                : sort == ComposerLibrary.Sort.TITLE ? MENU_SORT_TITLE : MENU_SORT_COMPOSITION);
        if (item != null) item.setChecked(true);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case MENU_SORT_COMPOSITION:
                sort = ComposerLibrary.Sort.COMPOSITION;
                buildRows();
                return true;
            case MENU_SORT_CATALOGUE:
                sort = ComposerLibrary.Sort.CATALOGUE;
                buildRows();
                return true;
            case MENU_SORT_TITLE:
                sort = ComposerLibrary.Sort.TITLE;
                buildRows();
                return true;
            case MENU_PLAYLIST:
                if (composer != null) savePlaylists(java.util.Collections.singletonList(composer));
                return true;
            case MENU_ALL_PLAYLISTS:
                confirmAllPlaylists();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmAllPlaylists() {
        if (library == null) return;
        List<ComposerLibrary.Composer> withChronology = new ArrayList<>();
        for (ComposerLibrary.Composer c : library.composers) {
            if (c.chrono != null && c.matchedWorkCount() > 0) withChronology.add(c);
        }
        if (withChronology.isEmpty()) {
            Toast.makeText(this, "None of your composers have works matched to a chronology yet.", Toast.LENGTH_LONG).show();
            return;
        }
        new MaterialDialog.Builder(this)
                .title("Composition-order playlists")
                .content("Create " + withChronology.size() + " playlists named \"<Composer>: Works in Order of "
                        + "Composition\", each with the recordings you have, one per work, in the order they were "
                        + "written? Existing playlists with these names are replaced.")
                .positiveText("Create")
                .negativeText(android.R.string.cancel)
                .onPositive((d, w) -> savePlaylists(withChronology))
                .show();
    }

    private void savePlaylists(List<ComposerLibrary.Composer> composers) {
        final android.content.Context app = getApplicationContext();
        Toast.makeText(this, "Creating playlists…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            int ok = 0, songs = 0;
            for (ComposerLibrary.Composer c : composers) {
                int n = ComposerPlaylists.write(app, c);
                if (n > 0) {
                    ok++;
                    songs += n;
                }
            }
            final String msg = composers.size() == 1
                    ? (ok == 1 ? "Saved \"" + ComposerPlaylists.playlistName(composers.get(0)) + "\" (" + songs + " tracks)"
                    : "No catalogued works to put in a playlist")
                    : "Created " + ok + " playlists (" + songs + " tracks)";
            main.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
        });
    }

    // ---------------------------------------------------------------------------------------
    // Playing
    // ---------------------------------------------------------------------------------------

    private void play(List<Song> songs) {
        if (songs.isEmpty()) return;
        MusicPlayerRemote.openQueue(songs, 0, true);
    }

    private void onWorkClicked(ComposerLibrary.Work work) {
        if (work.recordings.size() == 1) {
            play(work.recordings.get(0).tracks);
            return;
        }
        List<String> items = new ArrayList<>();
        for (ComposerLibrary.Recording r : work.recordings) {
            items.add(r.label() + (TextUtils.isEmpty(r.album) ? "" : "\n" + r.album) + "  (" + r.tracks.size()
                    + (r.tracks.size() == 1 ? " track)" : " tracks)"));
        }
        new MaterialDialog.Builder(this)
                .title(work.title)
                .items(items)
                .itemsCallback((d, v, which, text) -> play(work.recordings.get(which).tracks))
                .show();
    }

    // ---------------------------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------------------------

    private static final class Header {
    }

    private static final class Category {
        final String name;
        final List<ComposerLibrary.Work> works;

        Category(String name, List<ComposerLibrary.Work> works) {
            this.name = name;
            this.works = works;
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Object> rows;

        Adapter(List<Object> rows) {
            this.rows = rows;
        }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof Header) return 0;
            if (o instanceof Category) return 1;
            return 2;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) return new HeaderHolder();
            if (viewType == 1) return new CategoryHolder();
            return new RowHolder();
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind();
            else if (holder instanceof CategoryHolder) ((CategoryHolder) holder).bind((Category) o);
            else if (o instanceof ComposerLibrary.Composer) ((RowHolder) holder).bindComposer((ComposerLibrary.Composer) o);
            else ((RowHolder) holder).bindWork((ComposerLibrary.Work) o);
        }
    }

    private class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView summary;
        final Button playAll;

        HeaderHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(16), dp(16), dp(16), dp(4));
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            summary = text(R.style.TextAppearance_AppCompat_Body1);
            l.addView(summary);
            playAll = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            playAll.setTextColor(accent);
            playAll.setText("Play everything in this order");
            l.addView(playAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind() {
            if (composer == null) return;
            int matched = composer.matchedWorkCount();
            StringBuilder sb = new StringBuilder();
            sb.append(composer.works.size()).append(composer.works.size() == 1 ? " work, " : " works, ")
                    .append(composer.trackCount()).append(" tracks.");
            if (composer.chrono != null) {
                sb.append(" ").append(matched).append(" matched to the chronology of ")
                        .append(composer.chrono.works.size()).append(" works");
                if (matched < composer.works.size()) sb.append("; the rest are listed after them");
                sb.append(".");
            } else {
                sb.append(" No chronology for this composer yet, so works are sorted by title.");
            }
            summary.setText(sb);
            playAll.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(pages.isEmpty()
                    ? new ArrayList<>() : worksOf(pages.get(0)))));
        }
    }

    private class CategoryHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final Button play;

        CategoryHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(16), dp(16), dp(8), 0);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            name = text(R.style.TextAppearance_AppCompat_Subhead);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(accent);
            l.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            play = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            play.setTextColor(accent);
            play.setText("Play in order");
            l.addView(play);
            save = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            save.setTextColor(accent);
            save.setText("Save");
            l.addView(save);
        }

        final Button save;

        void bind(Category c) {
            name.setText(c.name + "  (" + c.works.size() + ")");
            play.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(c.works)));
            save.setOnClickListener(v -> askPlaylistName(defaultName(c.name), c.works));
        }
    }

    private class RowHolder extends RecyclerView.ViewHolder {
        final TextView title, subtitle;

        RowHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(16), dp(10), dp(16), dp(10));
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            l.setBackgroundResource(tv.resourceId);
            title = text(R.style.TextAppearance_AppCompat_Subhead);
            subtitle = text(R.style.TextAppearance_AppCompat_Caption);
            l.addView(title);
            l.addView(subtitle);
        }

        void bindComposer(ComposerLibrary.Composer c) {
            title.setText(c.name);
            StringBuilder sb = new StringBuilder();
            sb.append(c.works.size()).append(c.works.size() == 1 ? " work · " : " works · ")
                    .append(c.trackCount()).append(" tracks");
            if (c.chrono != null) sb.append(" · in order of composition");
            subtitle.setText(sb);
            itemView.setOnClickListener(v -> startActivity(new Intent(ComposersActivity.this, ComposersActivity.class)
                    .putExtra(EXTRA_COMPOSER, c.name)));
            itemView.setOnLongClickListener(null);
        }

        void bindWork(ComposerLibrary.Work w) {
            title.setText(w.title);
            List<String> parts = new ArrayList<>();
            if (!w.catalogue().isEmpty()) parts.add(w.catalogue());
            if (w.chrono != null && !w.chrono.date.isEmpty()) parts.add("composed " + w.chrono.date);
            if (w.recordings.size() == 1) parts.add(w.recordings.get(0).label());
            else parts.add(w.recordings.size() + " recordings");
            subtitle.setText(TextUtils.join(" · ", parts));
            itemView.setOnClickListener(v -> onWorkClicked(w));
            itemView.setOnLongClickListener(v -> {
                // Long press: always choose the recording.
                List<String> items = new ArrayList<>();
                for (ComposerLibrary.Recording r : w.recordings) items.add(r.label() + "\n" + r.album);
                new MaterialDialog.Builder(ComposersActivity.this)
                        .title(w.title)
                        .items(items)
                        .itemsCallback((d, vv, which, text) -> play(w.recordings.get(which).tracks))
                        .show();
                return true;
            });
        }
    }

    private TextView text(int appearance) {
        TextView t = new TextView(this);
        t.setTextAppearance(this, appearance);
        return t;
    }

    private int actionBarSize() {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(R.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
        }
        return dp(56);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

}
