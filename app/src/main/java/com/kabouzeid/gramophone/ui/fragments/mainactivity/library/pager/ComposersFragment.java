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
        implements LoaderManager.LoaderCallbacks<List<ComposerLibrary.Composer>> {

    private static final int LOADER_ID = LoaderIds.COMPOSERS_FRAGMENT;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
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
        List<ComposerLibrary.Composer> dataSet = getAdapter() == null ? new ArrayList<>() : getAdapter().dataSet;
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
    public Loader<List<ComposerLibrary.Composer>> onCreateLoader(int id, Bundle args) {
        return new AsyncComposerLoader(getActivity());
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<ComposerLibrary.Composer>> loader, List<ComposerLibrary.Composer> data) {
        getAdapter().swapDataSet(data);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<ComposerLibrary.Composer>> loader) {
        getAdapter().swapDataSet(new ArrayList<>());
    }

    private static class AsyncComposerLoader extends WrappedAsyncTaskLoader<List<ComposerLibrary.Composer>> {
        AsyncComposerLoader(Context context) {
            super(context);
        }

        @Override
        public List<ComposerLibrary.Composer> loadInBackground() {
            return new ArrayList<>(ComposerLibrary.get(getContext(), false).composers);
        }
    }

    public static class ComposerAdapter extends RecyclerView.Adapter<ComposerAdapter.ViewHolder> implements FastScrollRecyclerView.SectionedAdapter {
        private final AppCompatActivity activity;
        List<ComposerLibrary.Composer> dataSet;

        ComposerAdapter(AppCompatActivity activity, List<ComposerLibrary.Composer> dataSet) {
            this.activity = activity;
            this.dataSet = dataSet;
        }

        void swapDataSet(List<ComposerLibrary.Composer> dataSet) {
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
            ComposerLibrary.Composer c = dataSet.get(position);
            if (holder.separator != null) holder.separator.setVisibility(position == getItemCount() - 1 ? View.GONE : View.VISIBLE);
            if (holder.shortSeparator != null) holder.shortSeparator.setVisibility(View.GONE);
            if (holder.menu != null) holder.menu.setVisibility(View.GONE);
            if (holder.title != null) holder.title.setText(c.name);
            if (holder.text != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(c.works.size()).append(c.works.size() == 1 ? " work · " : " works · ")
                        .append(c.trackCount()).append(" tracks");
                if (c.chrono != null) sb.append(" · in order of composition");
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
            return MusicUtil.getSectionName(dataSet.get(position).sortName());
        }

        class ViewHolder extends MediaEntryViewHolder {
            ViewHolder(@NonNull View itemView) {
                super(itemView);
            }

            @Override
            public void onClick(View v) {
                int pos = getAdapterPosition();
                if (pos < 0) return;
                activity.startActivity(new Intent(activity, ComposersActivity.class)
                        .putExtra(ComposersActivity.EXTRA_COMPOSER, dataSet.get(pos).name));
            }
        }
    }
}
