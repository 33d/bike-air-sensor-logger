package au.id.dsp.bikeairsensorlogger.activity;


import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.util.LongSparseArray;
import android.support.v4.widget.SimpleCursorAdapter;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import au.id.dsp.bikeairsensorlogger.R;
import au.id.dsp.bikeairsensorlogger.bluetooth.BluetoothLoggerService;
import au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider;
import au.id.dsp.bikeairsensorlogger.bluetooth.DeviceConnection;
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

    /** Any views that are currently active */
    private final LongSparseArray<WeakReference<View>> views = new LongSparseArray<>();

    public CaptureListFragment() {
    }

    private final ServiceConnection loggerServiceConnection = new ServiceConnection() {
        private final Handler loggerHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case BluetoothLoggerService.MESSAGE_UPDATE:
                        if (msg.arg2 == DeviceConnection.State.IDLE.ordinal())
                            // Ooh, it's new!
                            getLoaderManager().restartLoader(0, null, loaderCallbacks);
                        else {
                            // Do we care about this ID?
                            WeakReference<View> ref = views.get(((BluetoothLoggerService.Device) msg.obj).getDbid());
                            View view = ref == null ? null : ref.get();
                            if (view != null)
                                ((TextView) view.findViewById(R.id.statusView)).setText(DeviceConnection.State.values()[msg.arg2].toString());
                        }
                        break;
                }
            }
        };

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            BluetoothLoggerService.Binder binder = (BluetoothLoggerService.Binder) iBinder;
            binder.register(loggerHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            loggerHandler.removeCallbacksAndMessages(null);
        }
    };

    private final LoaderManager.LoaderCallbacks<Cursor> loaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new CursorLoader(getActivity(), CaptureProvider.CAPTURES_WITH_COUNTS_URI,
                    null, // return all columns
                    null, null, // all rows
                    "START");
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
                null, // cursor not available yet
                new String[] { "NAME", "START", "COUNT" },
                new int[] { R.id.nameView, R.id.startTimeView, R.id.countView },
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

        final Intent intent = new Intent(activity, BluetoothLoggerService.class);
        activity.bindService(intent, loggerServiceConnection, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        getActivity().unbindService(loggerServiceConnection);
        try {
            logDatabase.close();
        } catch (IOException e) {
        }
    }
}
