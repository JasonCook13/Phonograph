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
import com.kabouzeid.gramophone.cleanup.TagExchange;
import com.kabouzeid.gramophone.cleanup.TagWriter;
import com.kabouzeid.gramophone.ui.activities.base.AbsBaseActivity;

import java.io.File;
import java.io.IOException;
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
    private static final int REQUEST_IMPORT = 4712;
    private static final int MENU_RESCAN = 1, MENU_EXPORT = 2, MENU_IMPORT = 3;
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
        /** Showing fixes imported from Claude instead of the tool's own suggestions. */
        boolean importMode;
        List<AlbumFix> imported = new ArrayList<>();
        final EnumSet<Category> enabledImported = EnumSet.of(Category.CLAUDE_SURE, Category.CLAUDE_CHECK);
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
        Button importButton = findViewById(R.id.cleanup_import);
        importButton.setTextColor(accent);
        importButton.setOnClickListener(v -> pickFixesFile());
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
        menu.add(0, MENU_RESCAN, 0, R.string.cleanup_rescan);
        menu.add(0, MENU_EXPORT, 1, "Export tags for Claude");
        menu.add(0, MENU_IMPORT, 2, "Import fixes from Claude");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean idle = !session.applying && session.state != Session.State.SCANNING;
        MenuItem rescan = menu.findItem(MENU_RESCAN);
        if (rescan != null) rescan.setVisible(session.state == Session.State.RESULTS && idle);
        MenuItem export = menu.findItem(MENU_EXPORT);
        if (export != null) export.setVisible(session.state == Session.State.RESULTS && idle && !session.files.isEmpty());
        MenuItem imp = menu.findItem(MENU_IMPORT);
        if (imp != null) imp.setVisible(idle);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        if (item.getItemId() == MENU_RESCAN) {
            session.decided.clear();
            session.importMode = false;
            startScan();
            return true;
        }
        if (item.getItemId() == MENU_EXPORT) {
            exportTags();
            return true;
        }
        if (item.getItemId() == MENU_IMPORT) {
            pickFixesFile();
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
        s.importMode = false;
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
            List<AlbumFix> fixes = CleanupPlanner.plan(s.files, s.enabled, new CleanupScanner.FolderCovers(), composers(app));
            main.post(() -> {
                s.fixes = fixes;
                restoreDecisions(s);
                s.state = Session.State.RESULTS;
                refresh();
            });
        });
    }

    /** Classical detection for the planner: composer tag, composer folders, known composers. */
    private static CleanupPlanner.ComposerLookup composers(android.content.Context app) {
        final com.kabouzeid.gramophone.classical.Chronology chronology =
                com.kabouzeid.gramophone.classical.ChronologyLoader.get(app);
        return f -> com.kabouzeid.gramophone.classical.ClassicalParser.composerOf(
                chronology, f.title, f.artist, f.albumArtist, f.composer, f.path);
    }

    private void replan() {
        final Session s = session;
        if (s.importMode) {
            s.fixes = filterImported(s);
            restoreDecisions(s);
            render();
            return;
        }
        final EnumSet<Category> enabled = EnumSet.copyOf(s.enabled);
        final android.content.Context app = getApplicationContext();
        EXECUTOR.execute(() -> {
            List<AlbumFix> fixes = CleanupPlanner.plan(s.files, enabled, new CleanupScanner.FolderCovers(), composers(app));
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
    // Round trip through Claude
    // ---------------------------------------------------------------------------------------

    /** Writes every scanned song's tags to a CSV and opens the share sheet (Claude, Drive, email...). */
    private void exportTags() {
        final android.content.Context app = getApplicationContext();
        final List<FileTags> files = new ArrayList<>(session.files);
        Toast.makeText(this, "Preparing " + files.size() + " songs…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(() -> {
            try {
                File dir = app.getExternalFilesDir(null);
                if (dir == null) dir = app.getCacheDir();
                String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
                File out = new File(dir, "phonograph-tags-" + stamp + ".csv");
                TagExchange.exportCsv(files, out);
                Uri uri = androidx.core.content.FileProvider.getUriForFile(app, app.getPackageName(), out);
                main.post(() -> {
                    if (current == null) return;
                    android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/csv")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, out.getName())
                            .putExtra(android.content.Intent.EXTRA_TEXT, "Tags of " + files.size()
                                    + " songs from Phonograph. Please send back a phonograph-fixes file.")
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    current.startActivity(android.content.Intent.createChooser(send, "Send tags to Claude"));
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(app, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void pickFixesFile() {
        android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(pick, REQUEST_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    private void importFixes(Uri uri) {
        final android.content.Context app = getApplicationContext();
        final Session s = session;
        EXECUTOR.execute(() -> {
            try (java.io.InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Can't open the file");
                Map<Long, FileTags> known = new HashMap<>();
                for (FileTags f : s.files) known.put(f.id, f);
                TagExchange.ParseResult r = TagExchange.parseFixes(in, known);
                main.post(() -> {
                    s.imported = r.albums;
                    s.importMode = true;
                    s.decided.clear();
                    s.fixes = filterImported(s);
                    s.state = Session.State.RESULTS;
                    refresh();
                    String msg = "Imported " + r.changes + " changes for " + r.albums.size() + " albums";
                    if (r.skippedUnknownFile + r.skippedBadField > 0) {
                        msg += " (" + (r.skippedUnknownFile + r.skippedBadField) + " skipped)";
                    }
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(app, "Couldn't read the fixes file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Imported albums with only the changes whose confidence level is switched on. */
    private static List<AlbumFix> filterImported(Session s) {
        List<AlbumFix> out = new ArrayList<>();
        for (AlbumFix a : s.imported) {
            AlbumFix copy = new AlbumFix(a.key, a.title, a.artist, a.fileCount);
            copy.note = a.note;
            for (Change c : a.changes) if (s.enabledImported.contains(c.category)) copy.changes.add(c);
            if (!copy.changes.isEmpty()) out.add(copy);
        }
        return out;
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
        if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) importFixes(data.getData());
            return;
        }
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
            if (s.importMode) {
                sb.append("Fixes from Claude: ").append(changes).append(" changes across ").append(s.fixes.size()).append(" albums.");
                sb.append(" Changes marked \"check\" are ones Claude wasn't sure about; untick them to leave them out.");
                sb.append("\n\nTry Fix on a few albums first. Use ⋮ → Scan again to go back to the tool's own suggestions.");
            } else {
                sb.append("Read ").append(s.files.size()).append(" songs. ");
                if (s.fixes.isEmpty()) sb.append("Everything already looks consistent.");
                else sb.append("Suggested ").append(changes).append(" changes across ").append(s.fixes.size()).append(" albums.");
                if (s.unreadable > 0) sb.append("\n").append(s.unreadable).append(" files couldn't be read and are left alone.");
                sb.append("\n\nTry Fix on a few albums, check them in your library, then use Fix all.");
                sb.append(" For classical identification and other judgment calls, use ⋮ → Export tags for Claude.");
            }
            summary.setText(sb);

            final EnumSet<Category> enabled = s.importMode ? s.enabledImported : s.enabled;
            for (Category c : Category.values()) {
                CheckBox box = boxes.get(c);
                box.setVisibility(c.imported() == s.importMode ? View.VISIBLE : View.GONE);
                box.setOnCheckedChangeListener(null);
                box.setChecked(enabled.contains(c));
                Integer n = counts.get(c);
                box.setText(c.label + (enabled.contains(c) ? "  (" + (n == null ? 0 : n) + ")" : ""));
                box.setEnabled(!s.applying);
                box.setOnCheckedChangeListener((b, checked) -> {
                    if (session.applying) return;
                    boolean changed = checked ? enabled.add(c) : enabled.remove(c);
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
            String check = c.category == Category.CLAUDE_CHECK ? " (check)" : "";
            String why = c.note != null && !c.note.isEmpty() && group.size() == 1 ? " · " + c.note : "";
            if (group.size() > 1) {
                String who = group.size() == f.fileCount ? "All " + group.size() + " songs" : group.size() + " songs";
                lines.add("• " + who + ": " + describeValue(c) + check);
            } else {
                lines.add("• " + songName(c.file) + ": " + describeValue(c) + check + why);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (f.note != null && !f.note.isEmpty()) sb.append(f.note).append("\n");
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
                String field = c.field == Change.Field.ALBUM_ARTIST ? "album artist"
                        : c.field == Change.Field.GROUPING ? "work (grouping)"
                        : c.field.name().toLowerCase(java.util.Locale.ROOT);
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
