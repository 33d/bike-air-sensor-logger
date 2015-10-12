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
import android.widget.TextView;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import au.id.dsp.bikeairsensorlogger.R;
import au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider;
import au.id.dsp.bikeairsensorlogger.bluetooth.LogDatabase;

public class CaptureListFragment extends ListFragment {

    private static final DateFormat dateFormat;
    static {
        DateFormat f = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        if (f instanceof SimpleDateFormat)
            // Add the time zone
            f = new SimpleDateFormat(((SimpleDateFormat) f).toPattern() + " z");
        else
            f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        dateFormat = f;
    }

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

        final Cursor cursor = logDatabase.getCaptures().createCursor();
        adapter = new SimpleCursorAdapter(activity,
                R.layout.fragment_capture_list_row,
                cursor,
                new String[] { "NAME", "START" },
                new int[] { R.id.nameView, R.id.startTimeView },
                0);
        adapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                if (cursor.getColumnIndexOrThrow("START") == columnIndex)
                    ((TextView) view).setText(dateFormat.format(new Date(cursor.getLong(columnIndex))));
                else
                    return false;
                return true;
            }
        });
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
