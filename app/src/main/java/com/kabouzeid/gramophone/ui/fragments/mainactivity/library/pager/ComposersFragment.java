package com.kabouzeid.gramophone.ui.fragments.mainactivity.library.pager;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kabouzeid.gramophone.R;
import com.kabouzeid.gramophone.adapter.base.MediaEntryViewHolder;
import com.kabouzeid.gramophone.classical.ComposerLibrary;
import com.kabouzeid.gramophone.interfaces.LoaderIds;
import com.kabouzeid.gramophone.misc.WrappedAsyncTaskLoader;
import com.kabouzeid.gramophone.ui.activities.ComposersActivity;
import com.kabouzeid.gramophone.util.MusicUtil;
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Library tab listing composers; tapping one opens their works page. */
public class ComposersFragment extends AbsLibraryPagerRecyclerViewFragment<ComposersFragment.ComposerAdapter, LinearLayoutManager>
        implements LoaderManager.LoaderCallbacks<List<ComposerLibrary.Summary>> {

    private static final int LOADER_ID = LoaderIds.COMPOSERS_FRAGMENT;
    public static final int SORT_NAME = 0, SORT_WORKS = 1, SORT_PLAYS = 2;
    private static final String PREF_SORT = "composer_list_sort";

    public int getSort() {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(PREF_SORT, SORT_NAME);
    }

    public void setSort(int sort) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putInt(PREF_SORT, sort).apply();
        getLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // Show the last known list straight away; the loader replaces it once the library is ready.
        if (ComposerLibrary.peek() == null && getAdapter().dataSet.isEmpty() && getSort() == SORT_NAME) {
            getAdapter().swapDataSet(ComposerLibrary.loadSnapshot(requireContext()));
        }
        getLoaderManager().initLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    protected LinearLayoutManager createLayoutManager() {
        return new LinearLayoutManager(getActivity());
    }

    @NonNull
    @Override
    protected ComposerAdapter createAdapter() {
        List<ComposerLibrary.Summary> dataSet = getAdapter() == null ? new ArrayList<>() : getAdapter().dataSet;
        return new ComposerAdapter(getLibraryFragment().getMainActivity(), dataSet);
    }

    @Override
    protected int getEmptyMessage() {
        return R.string.no_composers;
    }

    @Override
    public void onMediaStoreChanged() {
        ComposerLibrary.invalidate();
        getLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    public Loader<List<ComposerLibrary.Summary>> onCreateLoader(int id, Bundle args) {
        return new AsyncComposerLoader(getActivity(), getSort());
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<ComposerLibrary.Summary>> loader, List<ComposerLibrary.Summary> data) {
        getAdapter().byName = getSort() == SORT_NAME;
        getAdapter().swapDataSet(data);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<ComposerLibrary.Summary>> loader) {
        // keep showing what we have
    }

    private static class AsyncComposerLoader extends WrappedAsyncTaskLoader<List<ComposerLibrary.Summary>> {
        private final int sort;

        AsyncComposerLoader(Context context, int sort) {
            super(context);
            this.sort = sort;
        }

        @Override
        public List<ComposerLibrary.Summary> loadInBackground() {
            List<ComposerLibrary.Summary> out = new ArrayList<>();
            for (ComposerLibrary.Composer c : sorted()) out.add(ComposerLibrary.Summary.of(c));
            return out;
        }

        private List<ComposerLibrary.Composer> sorted() {
            List<ComposerLibrary.Composer> list = new ArrayList<>(ComposerLibrary.get(getContext(), false).composers);
            if (sort == SORT_WORKS) {
                java.util.Collections.sort(list, (a, b) -> b.works.size() - a.works.size());
            } else if (sort == SORT_PLAYS) {
                final java.util.Map<Long, Float> scores = com.kabouzeid.gramophone.provider.SongPlayCountStore.getInstance(getContext()).getPlayScores();
                final java.util.Map<ComposerLibrary.Composer, Float> total = new java.util.HashMap<>();
                for (ComposerLibrary.Composer c : list) {
                    float t = 0;
                    for (ComposerLibrary.Work w : c.works)
                        for (ComposerLibrary.Recording r : w.recordings)
                            for (com.kabouzeid.gramophone.model.Song s : r.tracks) {
                                Float f = scores.get(s.id);
                                if (f != null) t += f;
                            }
                    total.put(c, t);
                }
                java.util.Collections.sort(list, (a, b) -> Float.compare(total.get(b), total.get(a)));
            }
            return list;
        }
    }

    public static class ComposerAdapter extends RecyclerView.Adapter<ComposerAdapter.ViewHolder> implements FastScrollRecyclerView.SectionedAdapter {
        private final AppCompatActivity activity;
        List<ComposerLibrary.Summary> dataSet;
        boolean byName = true;

        ComposerAdapter(AppCompatActivity activity, List<ComposerLibrary.Summary> dataSet) {
            this.activity = activity;
            this.dataSet = dataSet;
        }

        void swapDataSet(List<ComposerLibrary.Summary> dataSet) {
            this.dataSet = dataSet;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(activity).inflate(R.layout.item_list_no_image, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ComposerLibrary.Summary c = dataSet.get(position);
            if (holder.separator != null) holder.separator.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
            if (holder.shortSeparator != null) holder.shortSeparator.setVisibility(View.GONE);
            if (holder.menu != null) holder.menu.setVisibility(View.GONE);
            if (holder.title != null) holder.title.setText(c.name);
            if (holder.text != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(c.works).append(c.works == 1 ? " work · " : " works · ")
                        .append(c.tracks).append(c.tracks == 1 ? " track" : " tracks");
                if (c.chronology) sb.append(" · in order of composition");
                holder.text.setText(sb);
            }
        }

        @Override
        public int getItemCount() {
            return dataSet.size();
        }

        @NonNull
        @Override
        public String getSectionName(int position) {
            return byName ? MusicUtil.getSectionName(dataSet.get(position).sortName) : "";
        }

        class ViewHolder extends MediaEntryViewHolder {
            ViewHolder(@NonNull View itemView) {
                super(itemView);
            }

            @Override
            public void onClick(View v) {
                int pos = getAdapterPosition();
                if (pos < 0) return;
                Intent intent = new Intent(activity, ComposersActivity.class)
                        .putExtra(ComposersActivity.EXTRA_COMPOSER, dataSet.get(pos).name);
                if (title != null) ComposersActivity.open(activity, title, intent);
                else activity.startActivity(intent);
            }
        }
    }
}
