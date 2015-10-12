package au.id.dsp.bikeairsensorlogger.activity;


import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.io.IOException;

import au.id.dsp.bikeairsensorlogger.R;
import au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider;
import au.id.dsp.bikeairsensorlogger.bluetooth.LogDatabase;

public class CaptureListFragment extends ListFragment {

    private LogDatabase logDatabase;
    private SimpleCursorAdapter adapter;
    private ListView view;

    public CaptureListFragment() {
    }

    private final LoaderManager.LoaderCallbacks<Cursor> loaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new CursorLoader(getActivity(), CaptureProvider.CONTENT_URI,
                    null, // return all columns
                    null, null, // all rows
                    BaseColumns._ID);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            adapter.swapCursor(data);

            // The list should now be shown.
            if (isResumed()) {
                setListShown(true);
            } else {
                setListShownNoAnimation(true);
            }
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            adapter.swapCursor(null);
        }
    };

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getLoaderManager().initLoader(0, null, loaderCallbacks);
    }

    @Override
    public void onAttach(Activity activity) {
        logDatabase = new LogDatabase(activity);
        super.onAttach(activity);
        adapter = new SimpleCursorAdapter(activity,
                R.layout.fragment_capture_list_row,
                logDatabase.getCaptures().createCursor(),
                new String[] { "NAME", "START" },
                new int[] { R.id.nameView, R.id.startTimeView },
                0) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                super.bindView(view, context, cursor);
                // TODO: Count and status
            }
        };
        setListAdapter(adapter);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        try {
            logDatabase.close();
        } catch (IOException e) {
        }
    }
}
