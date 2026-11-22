package com.kabouzeid.gramophone.ui.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
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
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
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
import com.google.android.material.tabs.TabLayout;
import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.appthemehelper.util.ToolbarContentTintHelper;
import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.classical.Chronology;
import com.kabouzeid.gramophone.classical.ComposerLibrary;
import com.kabouzeid.gramophone.classical.ComposerPlaylists;
import com.kabouzeid.gramophone.helper.MusicPlayerRemote;
import com.kabouzeid.gramophone.model.Song;
import com.kabouzeid.gramophone.ui.activities.base.AbsSlidingMusicPanelActivity;
import com.kabouzeid.gramophone.util.BrandColors;
import com.kabouzeid.gramophone.util.MusicUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The classical tree: Composers -> a composer (tabs: All works, Orchestral, Concertos, Chamber, ...,
 * with the types of work as headings) -> one type of work. Tapping a name carries it up into the
 * toolbar next to the back arrow, and back down again on the way out.
 */
public class ComposersActivity extends AbsSlidingMusicPanelActivity {
    public static final String EXTRA_COMPOSER = "composer";
    public static final String EXTRA_GROUP = "group";
    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_ALBUM = "album";   // a set: one album's works of one type
    public static final String EXTRA_WORK = "work";     // one work: its recordings and movements
    private static final String EXTRA_FROM_X = "from_x", EXTRA_FROM_Y = "from_y",
            EXTRA_FROM_SIZE = "from_size", EXTRA_FROM_COLOR = "from_color";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static ComposerLibrary.Sort sort = ComposerLibrary.Sort.COMPOSITION;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String composerName, groupName, typeName, albumName, workKey;
    private ComposerLibrary library;
    private ComposerLibrary.Composer composer;
    private final List<Object> rows = new ArrayList<>();      // composer list / one type of work
    private final List<Page> pages = new ArrayList<>();       // a composer's tabs
    private RecyclerView list;
    private androidx.viewpager.widget.ViewPager pager;
    private TabLayout tabs;
    private PagesAdapter pagesAdapter;
    private ProgressBar progress;
    private TextView empty, titleView, subtitleView;
    private View body;
    private int accent;
    private String query = "";
    private boolean exiting;

    /** Opens a level of the tree, carrying the tapped name up into the new screen's toolbar. */
    public static void open(@NonNull Activity from, @NonNull TextView source, @NonNull Intent intent) {
        int[] loc = new int[2];
        source.getLocationInWindow(loc);
        intent.putExtra(EXTRA_FROM_X, (float) (loc[0] + source.getTotalPaddingLeft()));
        intent.putExtra(EXTRA_FROM_Y, (float) (loc[1] + source.getTotalPaddingTop()));
        intent.putExtra(EXTRA_FROM_SIZE, source.getTextSize());
        intent.putExtra(EXTRA_FROM_COLOR, source.getCurrentTextColor());
        from.startActivity(intent);
        from.overridePendingTransition(android.R.anim.fade_in, 0);
    }

    private boolean isSetPage() {
        return composerName != null && albumName != null;
    }

    private boolean isWorkPage() {
        return composerName != null && workKey != null;
    }

    private boolean isTypePage() {
        return composerName != null && typeName != null && albumName == null && workKey == null;
    }

    private boolean isComposerPage() {
        return composerName != null && typeName == null && albumName == null && workKey == null;
    }

    private Toolbar toolbar;
    private View searchBar;

    /** Called from super.onCreate(): builds the screen inside the sliding now-playing panel. */
    @Override
    protected View createContentView() {
        composerName = getIntent().getStringExtra(EXTRA_COMPOSER);
        groupName = getIntent().getStringExtra(EXTRA_GROUP);
        typeName = getIntent().getStringExtra(EXTRA_TYPE);
        albumName = getIntent().getStringExtra(EXTRA_ALBUM);
        workKey = getIntent().getStringExtra(EXTRA_WORK);
        accent = BrandColors.goldText(this);
        int primary = ThemeStore.primaryColor(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        getLayoutInflater().inflate(R.layout.status_bar, root, true);
        toolbar = new Toolbar(new androidx.appcompat.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AppCompat_Dark_ActionBar));
        toolbar.setBackgroundColor(primary);
        toolbar.setPopupTheme(R.style.ThemeOverlay_AppCompat_Light);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titleView = new TextView(this);
        titleView.setTextAppearance(this, R.style.TextAppearance_AppCompat_Widget_ActionBar_Title);
        titleView.setTextColor(ToolbarContentTintHelper.toolbarTitleColor(this, primary));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setIncludeFontPadding(false);
        titles.addView(titleView);
        subtitleView = new TextView(this);
        subtitleView.setTextAppearance(this, R.style.TextAppearance_AppCompat_Widget_ActionBar_Subtitle);
        subtitleView.setTextColor(ToolbarContentTintHelper.toolbarSubtitleColor(this, primary));
        subtitleView.setSingleLine(true);
        subtitleView.setVisibility(View.GONE);
        titles.addView(subtitleView);
        toolbar.addView(titles, new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actionBarSize()));

        LinearLayout bodyLayout = new LinearLayout(this);
        bodyLayout.setOrientation(LinearLayout.VERTICAL);
        body = bodyLayout;
        root.addView(bodyLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (isComposerPage()) {
            tabs = new TabLayout(this);
            tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
            tabs.setBackgroundColor(primary);
            tabs.setTabTextColors(ToolbarContentTintHelper.toolbarSubtitleColor(this, primary),
                    ToolbarContentTintHelper.toolbarTitleColor(this, primary));
            tabs.setSelectedTabIndicatorColor(BrandColors.GOLD);
            bodyLayout.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (isComposerPage()) {
            searchBar = searchBar();
            bodyLayout.addView(searchBar);
        }
        FrameLayout content = new FrameLayout(this);
        bodyLayout.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (isComposerPage()) {
            pager = new androidx.viewpager.widget.ViewPager(this);
            pager.setId(View.generateViewId());
            pagesAdapter = new PagesAdapter();
            pager.setAdapter(pagesAdapter);
            tabs.setupWithViewPager(pager);
            pager.addOnPageChangeListener(new androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
                @Override
                public void onPageSelected(int position) {
                    // search belongs to "All works"; the group tabs just list their works
                    searchBar.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                    if (position != 0) hideKeyboard();
                }
            });
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
        @android.annotation.SuppressLint("InflateParams")
        View panel = getLayoutInflater().inflate(R.layout.sliding_music_panel_layout, null);
        ((ViewGroup) panel.findViewById(R.id.content_container)).addView(root,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return panel;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setDrawUnderStatusbar();
        setStatusbarColorAuto();
        setNavigationbarColorAuto();
        setTaskDescriptionColorAuto();
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        String title = isSetPage() ? albumName : isWorkPage() ? getIntent().getStringExtra("work_title")
                : isTypePage() ? typeName : composerName != null ? composerName : getString(R.string.composers);
        if (title == null) title = composerName;
        setTitle(title);
        titleView.setText(title);
        if (isTypePage() || isSetPage() || isWorkPage()) {
            subtitleView.setText(composerName);
            subtitleView.setVisibility(View.VISIBLE);
        }

        if (getIntent().hasExtra(EXTRA_FROM_Y) && savedInstanceState == null) animateTitle(true, null);
        load(false);
    }

    // ---------------------------------------------------------------------------------------
    // The name travels between the tapped row and the toolbar
    // ---------------------------------------------------------------------------------------

    private void animateTitle(final boolean entering, final Runnable after) {
        final float fromX = getIntent().getFloatExtra(EXTRA_FROM_X, 0), fromY = getIntent().getFloatExtra(EXTRA_FROM_Y, 0);
        final float fromSize = getIntent().getFloatExtra(EXTRA_FROM_SIZE, titleView.getTextSize());
        final int fromColor = getIntent().getIntExtra(EXTRA_FROM_COLOR, titleView.getCurrentTextColor());
        final ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        titleView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                titleView.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] loc = new int[2];
                titleView.getLocationInWindow(loc);
                final float toX = loc[0], toY = loc[1];
                final float toSize = titleView.getTextSize();
                final int toColor = titleView.getCurrentTextColor();

                final TextView flying = new TextView(ComposersActivity.this);
                flying.setText(titleView.getText());
                flying.setSingleLine(true);
                flying.setIncludeFontPadding(false);
                flying.setTypeface(titleView.getTypeface());
                decor.addView(flying, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                titleView.setAlpha(0f);
                subtitleView.setAlpha(0f);
                if (entering) body.setAlpha(0f);

                ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(entering ? 380 : 300);
                anim.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
                final ArgbEvaluator argb = new ArgbEvaluator();
                anim.addUpdateListener(a -> {
                    float f = (float) a.getAnimatedValue();
                    float p = entering ? f : 1f - f;   // 0 = in the row, 1 = in the toolbar
                    flying.setTextSize(TypedValue.COMPLEX_UNIT_PX, fromSize + (toSize - fromSize) * p);
                    flying.setX(fromX + (toX - fromX) * p);
                    flying.setY(fromY + (toY - fromY) * p);
                    flying.setTextColor((int) argb.evaluate(p, fromColor, toColor));
                    body.setAlpha(p);
                    body.setTranslationY((1f - p) * dp(24));
                    subtitleView.setAlpha(p);
                });
                anim.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        decor.removeView(flying);
                        if (entering) titleView.setAlpha(1f);
                        if (after != null) after.run();
                    }
                });
                anim.start();
                return true;
            }
        });
        titleView.invalidate();
    }

    @Override
    public void onBackPressed() {
        if (handleBackPress()) return;  // collapse the now-playing panel first
        if (exiting) return;
        if (getIntent().hasExtra(EXTRA_FROM_Y)) {
            exiting = true;
            animateTitle(false, () -> {
                finish();
                overridePendingTransition(0, android.R.anim.fade_out);
            });
            return;
        }
        super.onBackPressed();
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
        composer = null;
        if (composerName != null) {
            for (ComposerLibrary.Composer c : library.composers) if (c.name.equals(composerName)) composer = c;
        }
        if (isComposerPage()) {
            buildPages();
            return;
        }
        rows.clear();
        if (isSetPage()) {
            if (composer != null) buildSetRows();
            empty.setText("Nothing found.");
        } else if (isWorkPage()) {
            ComposerLibrary.Work w = composer == null ? null : composer.findWork(workKey);
            if (w != null) buildWorkRows(w);
            empty.setText("Nothing found.");
        } else if (isTypePage()) {
            if (composer != null) {
                List<ComposerLibrary.Work> works = new ArrayList<>();
                for (ComposerLibrary.Work w : composer.works) {
                    String c = w.category == null || w.category.isEmpty() ? w.group : w.category;
                    if (typeName.equals(c) && (groupName == null || groupName.equals(w.group))) works.add(w);
                }
                ComposerLibrary.sortWorks(works, sort);
                if (!works.isEmpty()) {
                    rows.add(new GroupHeader(works));
                    rows.addAll(ComposerLibrary.setsAndLooseWorks(works));
                }
            }
            empty.setText("Nothing found.");
        } else {
            rows.addAll(library.composers);
            empty.setText("No classical music found.\n\nTracks show up here when they have a composer tag, sit in a "
                    + "composer folder like \"BACH, JOHANN SEBASTIAN (1685-1750)\", or start with a known composer's name.");
        }
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        //noinspection ConstantConditions
        list.getAdapter().notifyDataSetChanged();
        supportInvalidateOptionsMenu();
    }

    /** A set: the album's works of this type, each with its movements, playable from any movement. */
    private void buildSetRows() {
        List<ComposerLibrary.Work> works = new ArrayList<>();
        for (ComposerLibrary.Work w : composer.works) {
            String c = w.category == null || w.category.isEmpty() ? w.group : w.category;
            if (typeName != null && !typeName.equals(c)) continue;
            if (!ComposerLibrary.recordingsOn(w, albumName).isEmpty()) works.add(w);
        }
        ComposerLibrary.sortWorks(works, sort);
        if (works.isEmpty()) return;
        List<Song> queue = new ArrayList<>();
        List<Object> body = new ArrayList<>();
        int lastOrder = -1;
        for (int i = 0; i < works.size(); i++) {
            ComposerLibrary.Work w = works.get(i);
            // Members of one published set ("Six String Quartets, Op. 18") get a label above them.
            if (w.chrono != null && w.chrono.order != lastOrder) {
                int n = 0;
                for (ComposerLibrary.Work x : works) if (x.chrono != null && x.chrono.order == w.chrono.order) n++;
                if (n > 1) body.add(new SetLabel(w.chrono));
            }
            lastOrder = w.chrono == null ? -1 : w.chrono.order;
            List<ComposerLibrary.Recording> recs = ComposerLibrary.recordingsOn(w, albumName);
            body.add(new WorkHeader(w, recs.size() == 1 ? recs.get(0) : null, queue, queue.size()));
            for (ComposerLibrary.Recording r : recs) {
                for (Song song : r.tracks) {
                    body.add(new Movement(song, queue, queue.size(), r.tracks.size() > 1));
                    queue.add(song);
                }
            }
        }
        String performers = "";
        for (ComposerLibrary.Work w : works) {
            for (ComposerLibrary.Recording r : ComposerLibrary.recordingsOn(w, albumName)) if (!r.performers.isEmpty()) performers = r.performers;
            if (!performers.isEmpty()) break;
        }
        subtitleView.setText(performers.isEmpty() ? composerName : performers + " · " + composerName);
        rows.add(new PlayHeader(works.size() + (works.size() == 1 ? " work · " : " works · ") + queue.size()
                + (queue.size() == 1 ? " track · " : " tracks · ") + MusicUtil.getReadableDurationString(total(queue)), queue, true));
        rows.addAll(body);
    }

    /** One work: each recording of it with its movements, playable from any movement. */
    private void buildWorkRows(ComposerLibrary.Work w) {
        List<String> parts = new ArrayList<>();
        if (!w.catalogue().isEmpty()) parts.add(w.catalogue());
        if (w.chrono != null && !w.chrono.date.isEmpty()) parts.add("composed " + w.chrono.date);
        parts.add(w.recordings.size() + (w.recordings.size() == 1 ? " recording" : " recordings"));
        rows.add(new PlayHeader(TextUtils.join(" · ", parts), w.preferred().tracks, false));
        for (ComposerLibrary.Recording r : w.recordings) {
            rows.add(new RecordingHeader(r));
            for (int i = 0; i < r.tracks.size(); i++) rows.add(new Movement(r.tracks.get(i), r.tracks, i, r.tracks.size() > 1));
        }
    }

    private static long total(List<Song> songs) {
        long t = 0;
        for (Song s : songs) t += s.duration;
        return t;
    }

    /** Tabs: "All works", then one per group (Orchestral, Concertos, Chamber, ...) with types as headings. */
    private void buildPages() {
        String keepTitle = pages.isEmpty() ? null : pages.get(Math.min(pager.getCurrentItem(), pages.size() - 1)).title;
        pages.clear();
        if (composer != null) {
            List<ComposerLibrary.Work> works = filtered(composer.works);
            ComposerLibrary.sortWorks(works, sort);
            Page all = new Page("All works", null);
            all.rows.add(new Header());
            all.rows.addAll(works);
            pages.add(all);
            List<ComposerLibrary.Work> everything = new ArrayList<>(composer.works);
            ComposerLibrary.sortWorks(everything, sort);
            for (Map.Entry<String, LinkedHashMap<String, List<ComposerLibrary.Work>>> g : ComposerLibrary.byGroup(everything).entrySet()) {
                Page page = new Page(ComposerLibrary.groupLabel(g.getKey()), g.getKey());
                List<ComposerLibrary.Work> inGroup = new ArrayList<>();
                for (List<ComposerLibrary.Work> l : g.getValue().values()) inGroup.addAll(l);
                ComposerLibrary.sortWorks(inGroup, sort);
                page.rows.add(new GroupHeader(inGroup));
                for (Map.Entry<String, List<ComposerLibrary.Work>> t : g.getValue().entrySet()) {
                    String name = t.getKey().equals(g.getKey()) && g.getValue().size() > 1 ? "Other " + page.title.toLowerCase(Locale.getDefault()) : t.getKey();
                    page.rows.add(new Category(name, t.getValue(), true, t.getKey(), g.getKey()));
                    page.rows.addAll(ComposerLibrary.setsAndLooseWorks(t.getValue()));
                }
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
        final String title, group;
        final List<Object> rows = new ArrayList<>();

        Page(String title, String group) {
            this.title = title;
            this.group = group;
        }
    }

    private class PagesAdapter extends androidx.viewpager.widget.PagerAdapter {
        @Override
        public int getCount() {
            return pages.size();
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return pages.get(position).title.toUpperCase(Locale.getDefault());
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            RecyclerView rv = new RecyclerView(ComposersActivity.this);
            rv.setLayoutManager(new LinearLayoutManager(ComposersActivity.this));
            rv.setAdapter(new Adapter(pages.get(position).rows));
            rv.setClipToPadding(false);
            rv.setPadding(0, 0, 0, dp(16));
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

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchBar != null) imm.hideSoftInputFromWindow(searchBar.getWindowToken(), 0);
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
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                if (library != null) buildRows();
            }
        });
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

    /** Works on the screen: the tab being shown, or the type of work. */
    private List<ComposerLibrary.Work> visibleWorks() {
        if (isComposerPage()) {
            if (pages.isEmpty()) return new ArrayList<>();
            return worksOf(pages.get(Math.min(pager.getCurrentItem(), pages.size() - 1)).rows);
        }
        return worksOf(rows);
    }

    private static List<ComposerLibrary.Work> worksOf(List<Object> rows) {
        List<ComposerLibrary.Work> works = new ArrayList<>();
        for (Object o : rows) if (o instanceof ComposerLibrary.Work) works.add((ComposerLibrary.Work) o);
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

    /** Opens the work's own page: its recordings, each with its movements. */
    private void openWork(TextView from, ComposerLibrary.Work work) {
        open(this, from, new Intent(this, ComposersActivity.class)
                .putExtra(EXTRA_COMPOSER, composerName)
                .putExtra(EXTRA_WORK, work.key)
                .putExtra("work_title", work.title));
    }

    private void openSet(TextView from, ComposerLibrary.Set set, String type) {
        open(this, from, new Intent(this, ComposersActivity.class)
                .putExtra(EXTRA_COMPOSER, composerName)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_ALBUM, set.album));
    }

    // ---------------------------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------------------------

    /** Top of "All works": summary and play everything. */
    private static final class Header {
    }

    /** Top of a group tab: how many works, play the whole group in order. */
    private static final class GroupHeader {
        final List<ComposerLibrary.Work> works;

        GroupHeader(List<ComposerLibrary.Work> works) {
            this.works = works;
        }
    }

    /** Top of a set or work page: a summary line and Play. */
    private static final class PlayHeader {
        final String text;
        final List<Song> queue;
        final boolean withToggle;

        PlayHeader(String text, List<Song> queue, boolean withToggle) {
            this.text = text;
            this.queue = queue;
            this.withToggle = withToggle;
        }
    }

    /** "Op. 18 · Six String Quartets · 1798–1800" above the members of a published set. */
    private static final class SetLabel {
        final Chronology.Work chrono;

        SetLabel(Chronology.Work chrono) {
            this.chrono = chrono;
        }
    }

    /** A work inside a set page; tapping plays the set from this work. */
    private static final class WorkHeader {
        final ComposerLibrary.Work work;
        final ComposerLibrary.Recording recording;  // null when the album has more than one
        final List<Song> queue;
        final int start;

        WorkHeader(ComposerLibrary.Work work, ComposerLibrary.Recording recording, List<Song> queue, int start) {
            this.work = work;
            this.recording = recording;
            this.queue = queue;
            this.start = start;
        }
    }

    /** A recording inside a work page. */
    private static final class RecordingHeader {
        final ComposerLibrary.Recording recording;

        RecordingHeader(ComposerLibrary.Recording recording) {
            this.recording = recording;
        }
    }

    /** One movement; tapping plays its queue from here. */
    private static final class Movement {
        final Song song;
        final List<Song> queue;
        final int index;
        final boolean indent;

        Movement(Song song, List<Song> queue, int index, boolean indent) {
            this.song = song;
            this.queue = queue;
            this.index = index;
            this.indent = indent;
        }
    }

    /** A type of work heading ("String Quartets"); tappable to open that type on its own. */
    private static final class Category {
        final String name, type, group;
        final List<ComposerLibrary.Work> works;
        final boolean opens;

        Category(String name, List<ComposerLibrary.Work> works, boolean opens) {
            this(name, works, opens, name, null);
        }

        Category(String name, List<ComposerLibrary.Work> works, boolean opens, String type, String group) {
            this.name = name;
            this.works = works;
            this.opens = opens;
            this.type = type;
            this.group = group;
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
            if (o instanceof GroupHeader) return 3;
            if (o instanceof ComposerLibrary.Set) return 4;
            if (o instanceof PlayHeader) return 5;
            if (o instanceof SetLabel) return 6;
            if (o instanceof WorkHeader) return 7;
            if (o instanceof RecordingHeader) return 8;
            if (o instanceof Movement) return 9;
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
            if (viewType == 3) return new GroupHeaderHolder();
            if (viewType == 4) return new SetCardHolder();
            if (viewType == 5) return new PlayHeaderHolder();
            if (viewType == 6) return new SetLabelHolder();
            if (viewType == 7 || viewType == 8) return new RowHolder();
            if (viewType == 9) return new MovementHolder();
            return new RowHolder();
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind();
            else if (holder instanceof GroupHeaderHolder) ((GroupHeaderHolder) holder).bind((GroupHeader) o);
            else if (holder instanceof CategoryHolder) ((CategoryHolder) holder).bind((Category) o);
            else if (holder instanceof SetCardHolder) ((SetCardHolder) holder).bind((ComposerLibrary.Set) o);
            else if (holder instanceof PlayHeaderHolder) ((PlayHeaderHolder) holder).bind((PlayHeader) o);
            else if (holder instanceof SetLabelHolder) ((SetLabelHolder) holder).bind((SetLabel) o);
            else if (holder instanceof MovementHolder) ((MovementHolder) holder).bind((Movement) o);
            else if (o instanceof WorkHeader) ((RowHolder) holder).bindWorkHeader((WorkHeader) o);
            else if (o instanceof RecordingHeader) ((RowHolder) holder).bindRecording((RecordingHeader) o);
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
            l.setPadding(dp(16), dp(12), dp(16), dp(4));
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            summary = text(R.style.TextAppearance_AppCompat_Body1);
            l.addView(summary);
            playAll = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            playAll.setTextColor(accent);
            playAll.setText("Play in this order");
            l.addView(playAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            toggle = new FrameLayout(ComposersActivity.this);
            l.addView(toggle);
        }

        final FrameLayout toggle;

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
            toggle.removeAllViews();
            toggle.addView(orderToggle());
            playAll.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(pages.isEmpty()
                    ? new ArrayList<>() : worksOf(pages.get(0).rows))));
        }
    }

    private class GroupHeaderHolder extends RecyclerView.ViewHolder {
        final TextView count;
        final Button play;
        final FrameLayout toggle;

        GroupHeaderHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(16), dp(4), dp(8), 0);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout row = new LinearLayout(ComposersActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            count = text(R.style.TextAppearance_AppCompat_Body1);
            row.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            play = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            play.setTextColor(accent);
            row.addView(play);
            l.addView(row);
            toggle = new FrameLayout(ComposersActivity.this);
            l.addView(toggle);
        }

        void bind(GroupHeader g) {
            count.setText(g.works.size() + (g.works.size() == 1 ? " work" : " works"));
            play.setText(isTypePage() ? "Play in order" : "Play tab in order");
            play.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(g.works)));
            toggle.removeAllViews();
            toggle.addView(orderToggle());
        }
    }

    private class CategoryHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final Button play, save;

        CategoryHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setPadding(dp(8), dp(12), dp(8), 0);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            name = text(R.style.TextAppearance_AppCompat_Subhead);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(accent);
            name.setPadding(dp(8), dp(8), dp(8), dp(8));
            name.setCompoundDrawablePadding(dp(2));
            l.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            play = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            play.setTextColor(accent);
            play.setText("Play");
            l.addView(play);
            save = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            save.setTextColor(accent);
            save.setText("Save");
            l.addView(save);
        }

        void bind(Category c) {
            name.setText(c.opens ? c.name + "  (" + c.works.size() + ")  ›" : c.name + "  (" + c.works.size() + ")");
            play.setOnClickListener(v -> play(ComposerLibrary.tracksInOrder(c.works)));
            save.setOnClickListener(v -> askPlaylistName(defaultName(c.name), c.works));
            if (c.opens) {
                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                name.setBackgroundResource(tv.resourceId);
                name.setOnClickListener(v -> {
                    // carry just the name (no count) up to the toolbar
                    name.setText(c.name);
                    open(ComposersActivity.this, name, new Intent(ComposersActivity.this, ComposersActivity.class)
                            .putExtra(EXTRA_COMPOSER, composerName)
                            .putExtra(EXTRA_GROUP, c.group)
                            .putExtra(EXTRA_TYPE, c.type));
                    name.postDelayed(() -> bind(c), 400);
                });
            } else {
                name.setBackground(null);
                name.setOnClickListener(null);
                name.setClickable(false);
            }
        }
    }

    private class RowHolder extends RecyclerView.ViewHolder {
        final TextView title, subtitle;
        int titleColor;

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
            titleColor = title.getCurrentTextColor();
            l.addView(title);
            l.addView(subtitle);
        }

        void bindComposer(ComposerLibrary.Composer c) {
            plain();
            title.setText(c.name);
            StringBuilder sb = new StringBuilder();
            sb.append(c.works.size()).append(c.works.size() == 1 ? " work · " : " works · ")
                    .append(c.trackCount()).append(" tracks");
            if (c.chrono != null) sb.append(" · in order of composition");
            subtitle.setText(sb);
            itemView.setOnClickListener(v -> open(ComposersActivity.this, title,
                    new Intent(ComposersActivity.this, ComposersActivity.class).putExtra(EXTRA_COMPOSER, c.name)));
            itemView.setOnLongClickListener(null);
        }

        void bindWork(ComposerLibrary.Work w) {
            plain();
            title.setText(w.title);
            List<String> parts = new ArrayList<>();
            if (!w.catalogue().isEmpty()) parts.add(w.catalogue());
            if (w.chrono != null && !w.chrono.date.isEmpty()) parts.add("composed " + w.chrono.date);
            if (w.recordings.size() == 1) parts.add(w.recordings.get(0).label());
            else parts.add(w.recordings.size() + " recordings");
            subtitle.setText(TextUtils.join(" · ", parts));
            itemView.setOnClickListener(v -> openWork(title, w));
            itemView.setOnLongClickListener(v -> {
                play(w.preferred().tracks);  // long press: just play it
                return true;
            });
        }

        /** A work inside a set: bold title, catalogue and date; tap plays the set from here. */
        void bindWorkHeader(WorkHeader h) {
            plain();
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setText(h.work.title);
            List<String> parts = new ArrayList<>();
            if (!h.work.catalogue().isEmpty()) parts.add(h.work.catalogue());
            if (h.work.chrono != null && !h.work.chrono.date.isEmpty()) parts.add("composed " + h.work.chrono.date);
            if (h.recording != null && h.recording.year > 0) parts.add("recorded " + h.recording.year);
            subtitle.setText(TextUtils.join(" · ", parts));
            subtitle.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
            itemView.setPadding(dp(16), dp(14), dp(16), dp(2));
            itemView.setOnClickListener(v -> MusicPlayerRemote.openQueue(h.queue, h.start, true));
            itemView.setOnLongClickListener(v -> {
                openWork(title, h.work);  // long press: the work's own page (other recordings)
                return true;
            });
        }

        /** A recording inside a work's page: performers, album, year; tap plays it from the start. */
        void bindRecording(RecordingHeader h) {
            plain();
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(accent);
            title.setText(h.recording.performers.isEmpty() ? "Unknown performers" : h.recording.performers);
            List<String> parts = new ArrayList<>();
            if (!h.recording.album.isEmpty()) parts.add(h.recording.album);
            if (h.recording.year > 0) parts.add(String.valueOf(h.recording.year));
            parts.add(h.recording.tracks.size() + (h.recording.tracks.size() == 1 ? " track" : " tracks"));
            subtitle.setText(TextUtils.join(" · ", parts));
            itemView.setPadding(dp(16), dp(14), dp(16), dp(2));
            itemView.setOnClickListener(v -> play(h.recording.tracks));
            itemView.setOnLongClickListener(null);
        }

        private void plain() {
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(titleColor);
            subtitle.setVisibility(View.VISIBLE);
            itemView.setPadding(dp(16), dp(10), dp(16), dp(10));
        }
    }

    /** A movement: "I. Allegro con brio" with its length; tap plays from here. */
    private class MovementHolder extends RecyclerView.ViewHolder {
        final TextView name, length;

        MovementHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.HORIZONTAL);
            l.setGravity(Gravity.CENTER_VERTICAL);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            l.setBackgroundResource(tv.resourceId);
            name = text(R.style.TextAppearance_AppCompat_Body1);
            l.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            length = text(R.style.TextAppearance_AppCompat_Caption);
            length.setPadding(dp(12), 0, 0, 0);
            l.addView(length);
        }

        void bind(Movement m) {
            String t = m.song.title == null ? "" : m.song.title;
            int colon = t.lastIndexOf(": ");
            if (colon > 0) t = t.substring(colon + 2);   // "Work, Op. 67: I. Allegro" -> "I. Allegro"
            name.setText(t);
            length.setText(MusicUtil.getReadableDurationString(m.song.duration));
            itemView.setPadding(dp(m.indent ? 32 : 16), dp(8), dp(16), dp(8));
            itemView.setOnClickListener(v -> MusicPlayerRemote.openQueue(m.queue, m.index, true));
        }
    }

    /** Top of a set or work page. */
    private class PlayHeaderHolder extends RecyclerView.ViewHolder {
        final TextView summary;
        final Button play;
        final FrameLayout toggle;

        PlayHeaderHolder() {
            super(new LinearLayout(ComposersActivity.this));
            LinearLayout l = (LinearLayout) itemView;
            l.setOrientation(LinearLayout.VERTICAL);
            l.setPadding(dp(16), dp(8), dp(8), 0);
            l.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout row = new LinearLayout(ComposersActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            summary = text(R.style.TextAppearance_AppCompat_Body1);
            row.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            play = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            play.setTextColor(accent);
            play.setText("Play");
            row.addView(play);
            l.addView(row);
            toggle = new FrameLayout(ComposersActivity.this);
            l.addView(toggle);
        }

        void bind(PlayHeader h) {
            summary.setText(h.text);
            play.setOnClickListener(v -> play(h.queue));
            toggle.removeAllViews();
            if (h.withToggle) toggle.addView(orderToggle());
        }
    }

    /** "Op. 18 · Six String Quartets · 1798–1800" */
    private class SetLabelHolder extends RecyclerView.ViewHolder {
        final TextView label;

        SetLabelHolder() {
            super(new FrameLayout(ComposersActivity.this));
            FrameLayout f = (FrameLayout) itemView;
            f.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            label = text(R.style.TextAppearance_AppCompat_Caption);
            label.setTextColor(accent);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setAllCaps(true);
            label.setPadding(dp(16), dp(18), dp(16), 0);
            f.addView(label);
        }

        void bind(SetLabel l) {
            List<String> parts = new ArrayList<>();
            if (!l.chrono.catalogue.isEmpty()) parts.add(l.chrono.catalogue);
            parts.add(l.chrono.title);
            if (!l.chrono.date.isEmpty()) parts.add(l.chrono.date);
            label.setText(TextUtils.join(" · ", parts));
        }
    }

    /** A set card: album, performers, counts, Play; tap opens the set. */
    private class SetCardHolder extends RecyclerView.ViewHolder {
        final TextView album, info;
        final Button play;
        final View card;

        SetCardHolder() {
            super(new FrameLayout(ComposersActivity.this));
            FrameLayout f = (FrameLayout) itemView;
            f.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.setPadding(dp(12), dp(4), dp(12), dp(6));
            LinearLayout c = new LinearLayout(ComposersActivity.this);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(com.kabouzeid.appthemehelper.util.ATHUtil.resolveColor(ComposersActivity.this, R.attr.cardBackgroundColor));
            bg.setStroke(Math.max(1, dp(1) / 2), com.kabouzeid.appthemehelper.util.ATHUtil.resolveColor(ComposersActivity.this, R.attr.dividerColor));
            c.setBackground(bg);
            c.setPadding(dp(14), dp(10), dp(6), dp(10));
            c.setClickable(true);
            c.setFocusable(true);
            card = c;
            LinearLayout texts = new LinearLayout(ComposersActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            album = text(R.style.TextAppearance_AppCompat_Subhead);
            album.setTypeface(Typeface.DEFAULT_BOLD);
            info = text(R.style.TextAppearance_AppCompat_Caption);
            texts.addView(album);
            texts.addView(info);
            c.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            play = new Button(ComposersActivity.this, null, R.attr.borderlessButtonStyle);
            play.setTextColor(accent);
            play.setText("Play");
            c.addView(play);
            f.addView(c);
        }

        void bind(ComposerLibrary.Set set) {
            album.setText(set.album);
            List<String> parts = new ArrayList<>();
            if (!set.performers.isEmpty()) parts.add(set.performers);
            parts.add(set.works.size() + " works");
            parts.add(MusicUtil.getReadableDurationString(set.duration));
            info.setText(TextUtils.join(" · ", parts));
            String type = set.works.get(0).category == null || set.works.get(0).category.isEmpty() ? set.works.get(0).group : set.works.get(0).category;
            card.setOnClickListener(v -> openSet(album, set, type));
            play.setOnClickListener(v -> {
                List<Song> q = new ArrayList<>();
                List<ComposerLibrary.Work> ws = new ArrayList<>(set.works);
                ComposerLibrary.sortWorks(ws, sort);
                for (ComposerLibrary.Work w : ws)
                    for (ComposerLibrary.Recording r : ComposerLibrary.recordingsOn(w, set.album)) q.addAll(r.tracks);
                play(q);
            });
        }
    }

    /** "Composition order · Opus order": the gold one is active; tap the other to switch. */
    private View orderToggle() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        String[] labels = {"Composition order", "Opus order"};
        ComposerLibrary.Sort[] sorts = {ComposerLibrary.Sort.COMPOSITION, ComposerLibrary.Sort.CATALOGUE};
        for (int i = 0; i < 2; i++) {
            if (i == 1) {
                TextView dot = text(R.style.TextAppearance_AppCompat_Caption);
                dot.setText("·");
                l.addView(dot);
            }
            final ComposerLibrary.Sort target = sorts[i];
            TextView t = text(R.style.TextAppearance_AppCompat_Body1);
            t.setText(labels[i]);
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            boolean active = sort == target;
            if (active) {
                t.setTextColor(accent);
                t.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                t.setAlpha(0.7f);
                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                t.setBackgroundResource(tv.resourceId);
                t.setOnClickListener(v -> {
                    sort = target;
                    buildRows();
                });
            }
            l.addView(t);
        }
        return l;
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
