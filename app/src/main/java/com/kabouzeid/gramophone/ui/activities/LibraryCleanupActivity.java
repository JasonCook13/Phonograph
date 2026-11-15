package com.kabouzeid.gramophone.ui.activities;

import android.app.PendingIntent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.CompoundButtonCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.kabouzeid.appthemehelper.ThemeStore;
import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.cleanup.AlbumFix;
import com.kabouzeid.gramophone.cleanup.Category;
import com.kabouzeid.gramophone.cleanup.Change;
import com.kabouzeid.gramophone.cleanup.CleanupPlanner;
import com.kabouzeid.gramophone.cleanup.CleanupScanner;
import com.kabouzeid.gramophone.cleanup.FileTags;
import com.kabouzeid.gramophone.cleanup.TagWriter;
import com.kabouzeid.gramophone.ui.activities.base.AbsBaseActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Library cleanup: scan every song's tags, review proposed fixes album by album,
 * try "Fix" on a few, then "Fix all".
 */
public class LibraryCleanupActivity extends AbsBaseActivity {
    private static final int REQUEST_WRITE_PERMISSION = 4711;
    private static final int MAX_URIS_PER_REQUEST = 500;

    /** Survives rotation / leaving the screen; the scan is expensive. */
    private static final class Session {
        enum State {INTRO, SCANNING, RESULTS}

        State state = State.INTRO;
        int progressDone, progressTotal;
        List<FileTags> files = new ArrayList<>();
        int unreadable;
        final EnumSet<Category> enabled = EnumSet.allOf(Category.class);
        List<AlbumFix> fixes = new ArrayList<>();
        /** Album key -> final status, so re-planning keeps what the user already did. */
        final Map<String, AlbumFix.Status> decided = new HashMap<>();
        boolean applying;
        int applyDone, applyTotal;
        /** Albums waiting for the user to grant write access (Android 11+). */
        final List<AlbumFix> waitingForPermission = new ArrayList<>();
        volatile boolean cancelled;
    }

    private static Session session = new Session();
    /** The screen currently showing, so background work updates the right one after rotation. */
    @Nullable
    private static LibraryCleanupActivity current;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    private ScrollView intro;
    private View progress;
    private ProgressBar progressBar;
    private TextView progressText;
    private RecyclerView list;
    private View bottomBar;
    private TextView bottomText;
    private Button fixAll;
    private Adapter adapter;
    private int accent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library_cleanup);
        setDrawUnderStatusbar();
        setStatusbarColorAuto();
        setNavigationbarColorAuto();
        setTaskDescriptionColorAuto();

        accent = ThemeStore.accentColor(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setBackgroundColor(ThemeStore.primaryColor(this));
        setSupportActionBar(toolbar);
        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle(R.string.library_cleanup);

        intro = findViewById(R.id.cleanup_intro);
        progress = findViewById(R.id.cleanup_progress);
        progressBar = findViewById(R.id.cleanup_progress_bar);
        progressText = findViewById(R.id.cleanup_progress_text);
        list = findViewById(R.id.cleanup_list);
        bottomBar = findViewById(R.id.cleanup_bottom_bar);
        bottomText = findViewById(R.id.cleanup_bottom_text);
        fixAll = findViewById(R.id.cleanup_fix_all);

        Button scan = findViewById(R.id.cleanup_scan);
        tintButton(scan);
        tintButton(fixAll);
        scan.setOnClickListener(v -> startScan());
        fixAll.setOnClickListener(v -> confirmFixAll());

        adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        current = this;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        render();
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    private static void refresh() {
        if (current != null && !current.isFinishing()) current.render();
    }

    private static void refreshProgress() {
        if (current != null && !current.isFinishing()) current.renderProgress();
    }

    private static void refreshList() {
        if (current != null && !current.isFinishing()) current.renderAfterChange();
    }

    @Override
    protected String[] getPermissionsToRequest() {
        return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    @Override
    protected View getSnackBarContainer() {
        return findViewById(R.id.cleanup_root);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.cleanup_rescan);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem rescan = menu.findItem(1);
        if (rescan != null) rescan.setVisible(session.state == Session.State.RESULTS && !session.applying);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        if (item.getItemId() == 1) {
            session.decided.clear();
            startScan();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------------------------------
    // Scanning & planning
    // ---------------------------------------------------------------------------------------

    private void startScan() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        final Session s = session;
        s.state = Session.State.SCANNING;
        s.progressDone = 0;
        s.progressTotal = 0;
        s.cancelled = false;
        render();
        final android.content.Context app = getApplicationContext();
        EXECUTOR.execute(() -> {
            CleanupScanner.Result result = CleanupScanner.scan(app, (done, total) -> {
                s.progressDone = done;
                s.progressTotal = total;
                if (done % 25 == 0 || done == total) main.post(LibraryCleanupActivity::refreshProgress);
                return !s.cancelled;
            });
            s.files = result.files;
            s.unreadable = result.unreadable;
            List<AlbumFix> fixes = CleanupPlanner.plan(s.files, s.enabled, new CleanupScanner.FolderCovers());
            main.post(() -> {
                s.fixes = fixes;
                restoreDecisions(s);
                s.state = Session.State.RESULTS;
                refresh();
            });
        });
    }

    private void replan() {
        final Session s = session;
        final EnumSet<Category> enabled = EnumSet.copyOf(s.enabled);
        EXECUTOR.execute(() -> {
            List<AlbumFix> fixes = CleanupPlanner.plan(s.files, enabled, new CleanupScanner.FolderCovers());
            main.post(() -> {
                s.fixes = fixes;
                restoreDecisions(s);
                refresh();
            });
        });
    }

    private static void restoreDecisions(Session s) {
        for (AlbumFix f : s.fixes) {
            AlbumFix.Status st = s.decided.get(f.key);
            if (st != null) f.status = st;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Applying
    // ---------------------------------------------------------------------------------------

    private void confirmFixAll() {
        List<AlbumFix> pending = pending();
        if (pending.isEmpty()) return;
        int changes = 0, files = 0;
        for (AlbumFix f : pending) {
            changes += f.changes.size();
            files += f.files().size();
        }
        new MaterialDialog.Builder(this)
                .title(R.string.cleanup_fix_all)
                .content("Write " + changes + " changes into " + files + " music files across "
                        + pending.size() + " albums?\n\nSkipped albums are left alone. This changes the files "
                        + "themselves, so make sure your backup is up to date.")
                .positiveText(R.string.cleanup_fix_all)
                .negativeText(android.R.string.cancel)
                .onPositive((d, w) -> apply(pending))
                .show();
    }

    private List<AlbumFix> pending() {
        List<AlbumFix> pending = new ArrayList<>();
        for (AlbumFix f : session.fixes) {
            if (f.status == AlbumFix.Status.PENDING || f.status == AlbumFix.Status.FAILED) pending.add(f);
        }
        return pending;
    }

    private void apply(final List<AlbumFix> albums) {
        final Session s = session;
        if (s.applying || albums.isEmpty()) return;
        s.applying = true;
        s.applyDone = 0;
        s.applyTotal = albums.size();
        for (AlbumFix f : albums) {
            f.status = AlbumFix.Status.FIXING;
            f.message = null;
        }
        render();

        final android.content.Context app = getApplicationContext();
        EXECUTOR.execute(() -> {
            TagWriter writer = new TagWriter(app);
            List<String> touched = new ArrayList<>();
            List<AlbumFix> needPermission = new ArrayList<>();
            for (AlbumFix album : albums) {
                Outcome o = writeAlbum(writer, album, touched);
                if (o == Outcome.NEEDS_PERMISSION) needPermission.add(album);
                s.applyDone++;
                main.post(LibraryCleanupActivity::refreshList);
            }
            scan(app, touched);
            final String log = writer.getLogFile().getPath();
            main.post(() -> {
                s.applying = false;
                if (!needPermission.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && current != null) {
                    s.waitingForPermission.clear();
                    s.waitingForPermission.addAll(needPermission);
                    current.requestWriteAccess(needPermission);
                } else {
                    for (AlbumFix f : needPermission) {
                        markFailed(f, "Android didn't allow changing these files");
                    }
                }
                refresh();
                if (albums.size() > 1) {
                    Toast.makeText(app, "Done. Changes are logged in " + log, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private enum Outcome {OK, NEEDS_PERMISSION, FAILED}

    /** Writes one album's changes. Runs on the executor. */
    private Outcome writeAlbum(TagWriter writer, AlbumFix album, List<String> touched) {
        Map<FileTags, List<Change>> byFile = new LinkedHashMap<>();
        for (Change c : album.changes) {
            List<Change> l = byFile.get(c.file);
            if (l == null) {
                l = new ArrayList<>();
                byFile.put(c.file, l);
            }
            l.add(c);
        }
        int failed = 0;
        boolean needsPermission = false;
        String error = null;
        for (Map.Entry<FileTags, List<Change>> e : byFile.entrySet()) {
            TagWriter.Result r = writer.write(e.getKey(), e.getValue());
            if (r.outcome == TagWriter.Outcome.OK) {
                touched.add(e.getKey().path);
            } else if (r.outcome == TagWriter.Outcome.NEEDS_PERMISSION) {
                needsPermission = true;
            } else {
                failed++;
                error = r.error;
            }
        }
        if (needsPermission) {
            return Outcome.NEEDS_PERMISSION;
        }
        if (failed > 0) {
            final String msg = failed + " of " + byFile.size() + " files couldn't be changed (" + error + ")";
            main.post(() -> markFailed(album, msg));
            return Outcome.FAILED;
        }
        main.post(() -> {
            album.status = AlbumFix.Status.FIXED;
            session.decided.put(album.key, AlbumFix.Status.FIXED);
        });
        return Outcome.OK;
    }

    private void markFailed(AlbumFix album, String message) {
        album.status = AlbumFix.Status.FAILED;
        album.message = message;
    }

    /** Android 11+: ask once for write access to all files that need it, then retry them. */
    private void requestWriteAccess(List<AlbumFix> albums) {
        List<Uri> uris = new ArrayList<>();
        for (AlbumFix a : albums) {
            for (FileTags f : a.files()) {
                if (uris.size() < MAX_URIS_PER_REQUEST) uris.add(TagWriter.contentUri(f.id));
            }
        }
        try {
            PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), uris);
            startIntentSenderForResult(pi.getIntentSender(), REQUEST_WRITE_PERMISSION, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            for (AlbumFix a : albums) markFailed(a, "Couldn't ask for permission: " + e.getMessage());
            session.waitingForPermission.clear();
            render();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_WRITE_PERMISSION) return;
        List<AlbumFix> waiting = new ArrayList<>(session.waitingForPermission);
        session.waitingForPermission.clear();
        if (resultCode == RESULT_OK) {
            // Granted: run them again (more than MAX_URIS_PER_REQUEST files will ask again).
            for (AlbumFix a : waiting) a.status = AlbumFix.Status.PENDING;
            apply(waiting);
        } else {
            for (AlbumFix a : waiting) markFailed(a, "Permission to change these files was denied");
            render();
        }
    }

    private static void scan(android.content.Context app, List<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(app, paths.toArray(new String[0]), null, null);
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private void render() {
        Session s = session;
        intro.setVisibility(s.state == Session.State.INTRO ? View.VISIBLE : View.GONE);
        progress.setVisibility(s.state == Session.State.SCANNING ? View.VISIBLE : View.GONE);
        list.setVisibility(s.state == Session.State.RESULTS ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(s.state == Session.State.RESULTS ? View.VISIBLE : View.GONE);
        renderProgress();
        renderAfterChange();
        supportInvalidateOptionsMenu();
    }

    private void renderProgress() {
        Session s = session;
        progressBar.setMax(Math.max(1, s.progressTotal));
        progressBar.setProgress(s.progressDone);
        progressText.setText(s.progressTotal == 0 ? "Finding songs…"
                : "Reading tags… " + s.progressDone + " / " + s.progressTotal);
    }

    private void renderAfterChange() {
        Session s = session;
        adapter.notifyDataSetChanged();
        int pending = pending().size();
        if (s.applying) {
            bottomText.setText("Fixing… " + s.applyDone + " / " + s.applyTotal + " albums");
        } else if (!s.waitingForPermission.isEmpty()) {
            bottomText.setText("Waiting for permission…");
        } else {
            bottomText.setText(pending == 0 ? "Nothing left to fix" : pending + " albums to fix");
        }
        fixAll.setEnabled(!s.applying && s.waitingForPermission.isEmpty() && pending > 0);
    }

    private void tintButton(Button b) {
        androidx.core.view.ViewCompat.setBackgroundTintList(b, ColorStateList.valueOf(accent));
    }

    // ---------------------------------------------------------------------------------------
    // List
    // ---------------------------------------------------------------------------------------

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ALBUM = 1;

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_ALBUM;
        }

        @Override
        public int getItemCount() {
            return session.state == Session.State.RESULTS ? 1 + session.fixes.size() : 0;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_HEADER) return new HeaderHolder(parent);
            return new AlbumHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cleanup_album, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderHolder) ((HeaderHolder) holder).bind();
            else ((AlbumHolder) holder).bind(session.fixes.get(position - 1));
        }
    }

    private class HeaderHolder extends RecyclerView.ViewHolder {
        final LinearLayout root;
        final TextView summary;
        final Map<Category, CheckBox> boxes = new EnumMap<>(Category.class);

        HeaderHolder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            root = (LinearLayout) itemView;
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(16);
            root.setPadding(pad, pad, pad, dp(8));
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            summary = new TextView(parent.getContext());
            summary.setTextAppearance(parent.getContext(), R.style.TextAppearance_AppCompat_Body1);
            summary.setLineSpacing(0, 1.15f);
            root.addView(summary);

            TextView hint = new TextView(parent.getContext());
            hint.setTextAppearance(parent.getContext(), R.style.TextAppearance_AppCompat_Caption);
            hint.setText("Untick a kind of fix to leave it out:");
            hint.setPadding(0, dp(12), 0, dp(4));
            root.addView(hint);

            for (Category c : Category.values()) {
                CheckBox box = new CheckBox(parent.getContext());
                CompoundButtonCompat.setButtonTintList(box, ColorStateList.valueOf(accent));
                box.setOnCheckedChangeListener((b, checked) -> {
                    if (session.applying) return;
                    boolean changed = checked ? session.enabled.add(c) : session.enabled.remove(c);
                    if (changed) replan();
                });
                boxes.put(c, box);
                root.addView(box);
            }
        }

        void bind() {
            Session s = session;
            Map<Category, Integer> counts = new EnumMap<>(Category.class);
            int changes = 0;
            for (AlbumFix f : s.fixes) {
                for (Change c : f.changes) {
                    Integer n = counts.get(c.category);
                    counts.put(c.category, n == null ? 1 : n + 1);
                    changes++;
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Read ").append(s.files.size()).append(" songs. ");
            if (s.fixes.isEmpty()) sb.append("Everything already looks consistent.");
            else sb.append("Suggested ").append(changes).append(" changes across ").append(s.fixes.size()).append(" albums.");
            if (s.unreadable > 0) sb.append("\n").append(s.unreadable).append(" files couldn't be read and are left alone.");
            sb.append("\n\nTry Fix on a few albums, check them in your library, then use Fix all.");
            summary.setText(sb);

            for (Category c : Category.values()) {
                CheckBox box = boxes.get(c);
                box.setOnCheckedChangeListener(null);
                box.setChecked(s.enabled.contains(c));
                Integer n = counts.get(c);
                box.setText(c.label + (s.enabled.contains(c) ? "  (" + (n == null ? 0 : n) + ")" : ""));
                box.setEnabled(!s.applying);
                box.setOnCheckedChangeListener((b, checked) -> {
                    if (session.applying) return;
                    boolean changed = checked ? session.enabled.add(c) : session.enabled.remove(c);
                    if (changed) replan();
                });
            }
        }
    }

    private class AlbumHolder extends RecyclerView.ViewHolder {
        final TextView title, subtitle, changes, status;
        final Button fix, skip;

        AlbumHolder(View v) {
            super(v);
            title = v.findViewById(R.id.album_title);
            subtitle = v.findViewById(R.id.album_subtitle);
            changes = v.findViewById(R.id.album_changes);
            status = v.findViewById(R.id.album_status);
            fix = v.findViewById(R.id.album_fix);
            skip = v.findViewById(R.id.album_skip);
            fix.setTextColor(accent);
        }

        void bind(AlbumFix f) {
            title.setText(f.title.isEmpty() ? "(no album)" : f.title);
            subtitle.setText((f.artist.isEmpty() ? "Unknown artist" : f.artist) + " • " + f.fileCount
                    + (f.fileCount == 1 ? " song" : " songs") + " • " + f.changes.size()
                    + (f.changes.size() == 1 ? " change" : " changes"));
            changes.setText(describe(f));

            boolean busy = session.applying || !session.waitingForPermission.isEmpty();
            switch (f.status) {
                case FIXED:
                    status.setText("✓ Fixed");
                    status.setTextColor(0xFF2E7D32);
                    break;
                case FAILED:
                    status.setText("✗ " + (f.message == null ? "Failed" : f.message));
                    status.setTextColor(0xFFC62828);
                    break;
                case FIXING:
                    status.setText("Fixing…");
                    status.setTextColor(accent);
                    break;
                case SKIPPED:
                    status.setText("Skipped");
                    status.setTextColor(0xFF757575);
                    break;
                default:
                    status.setText("");
            }
            boolean actionable = f.status == AlbumFix.Status.PENDING || f.status == AlbumFix.Status.FAILED;
            fix.setVisibility(actionable ? View.VISIBLE : View.GONE);
            fix.setText(f.status == AlbumFix.Status.FAILED ? "Retry" : getString(R.string.cleanup_fix));
            fix.setEnabled(!busy);
            fix.setOnClickListener(v -> {
                List<AlbumFix> one = new ArrayList<>();
                one.add(f);
                apply(one);
            });

            skip.setVisibility(f.status == AlbumFix.Status.FIXED || f.status == AlbumFix.Status.FIXING ? View.GONE : View.VISIBLE);
            skip.setText(f.status == AlbumFix.Status.SKIPPED ? R.string.cleanup_undo_skip : R.string.cleanup_skip);
            skip.setEnabled(!busy);
            skip.setOnClickListener(v -> {
                if (f.status == AlbumFix.Status.SKIPPED) {
                    f.status = AlbumFix.Status.PENDING;
                    session.decided.remove(f.key);
                } else {
                    f.status = AlbumFix.Status.SKIPPED;
                    session.decided.put(f.key, AlbumFix.Status.SKIPPED);
                }
                renderAfterChange();
            });
        }
    }

    // ---------------------------------------------------------------------------------------
    // Describing changes
    // ---------------------------------------------------------------------------------------

    private static final int MAX_LINES = 6;

    /** Album-wide changes are summarised ("All 12 songs: album artist → Drake"). */
    private static String describe(AlbumFix f) {
        Map<String, List<Change>> same = new LinkedHashMap<>();
        for (Change c : f.changes) {
            String k = c.field + "\u0000" + c.newValue;
            List<Change> l = same.get(k);
            if (l == null) {
                l = new ArrayList<>();
                same.put(k, l);
            }
            l.add(c);
        }
        List<String> lines = new ArrayList<>();
        for (List<Change> group : same.values()) {
            Change c = group.get(0);
            if (group.size() > 1) {
                String who = group.size() == f.fileCount ? "All " + group.size() + " songs" : group.size() + " songs";
                lines.add("• " + who + ": " + describeValue(c));
            } else {
                lines.add("• " + songName(c.file) + ": " + describeValue(c));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        if (lines.size() > MAX_LINES) sb.append("\n…and ").append(lines.size() - MAX_LINES).append(" more");
        return sb.toString();
    }

    private static String describeValue(Change c) {
        switch (c.field) {
            case ARTWORK:
                return "add album art from " + new File(c.newValue).getName();
            case TRACK:
                return "track number " + c.newValue;
            default:
                String field = c.field == Change.Field.ALBUM_ARTIST ? "album artist" : c.field.name().toLowerCase();
                return c.oldValue.isEmpty()
                        ? field + " → \"" + c.newValue + "\""
                        : field + " \"" + c.oldValue + "\" → \"" + c.newValue + "\"";
        }
    }

    private static String songName(FileTags f) {
        if (!f.title.isEmpty()) return f.title;
        return new File(f.path).getName();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
