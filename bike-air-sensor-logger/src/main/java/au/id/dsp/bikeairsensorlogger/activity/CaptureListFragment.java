package au.id.dsp.bikeairsensorlogger.activity;


import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.util.LongSparseArray;
import android.support.v4.widget.SimpleCursorAdapter;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import au.id.dsp.bikeairsensorlogger.R;
import au.id.dsp.bikeairsensorlogger.bluetooth.BluetoothLoggerService;
import au.id.dsp.bikeairsensorlogger.bluetooth.CaptureProvider;
import au.id.dsp.bikeairsensorlogger.bluetooth.DeviceConnection;
import au.id.dsp.bikeairsensorlogger.bluetooth.LogDatabase;

public class CaptureListFragment extends ListFragment {

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private LogDatabase logDatabase;
    private SimpleCursorAdapter adapter;
    private ListView view;
    private BluetoothLoggerService.Binder service;

    /** Any views that are currently active */
    private final LongSparseArray<WeakReference<View>> views = new LongSparseArray<>();

    public CaptureListFragment() {
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        getListView().setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE);
    }

    private final ServiceConnection loggerServiceConnection = new ServiceConnection() {
        private final Handler loggerHandler = new Handler(Looper.getMainLooper()) {
            /** The database ID for each capture ID */
            private SparseArray<Long> dbIDs = new SparseArray<>();
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case BluetoothLoggerService.MESSAGE_UPDATE:
                        // Find the view for this ID, if it's being used
                        WeakReference<View> ref = views.get(((BluetoothLoggerService.Device) msg.obj).getDbid());
                        View view = ref == null ? null : ref.get();
                        if (msg.arg2 == DeviceConnection.State.CLOSED.ordinal()) {
                            dbIDs.remove(msg.arg1);
                            if (view != null)
                                ((TextView) view.findViewById(R.id.statusView)).setText("");
                        } else {
                            if (view != null) {
                                String text = msg.arg2 == DeviceConnection.State.ERROR.ordinal()
                                    ? ((BluetoothLoggerService.Device) msg.obj).getLastError().getLocalizedMessage()
                                    : DeviceConnection.State.values()[msg.arg2].toString();
                                ((TextView) view.findViewById(R.id.statusView)).setText(text);
                            }
                        }
                        break;
                }
            }
        };

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            service = (BluetoothLoggerService.Binder) iBinder;
            service.register(loggerHandler);
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
                0) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                // Is this view being recycled?
                if (view != null && view.getTag() != null) {
                    views.remove((Long) view.getTag());
                    view.setTag(null);
                }
                super.bindView(view, context, cursor);
                long id = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
                // If this is a live capture, provide status updates
                String status = "";
                if (service != null) {
                    BluetoothLoggerService.Device device = service.getDevice(cursor.getLong(cursor.getColumnIndex(BaseColumns._ID)));
                    if (device != null) {
                        status = device.getState().toString();
                        view.setTag(id);
                        views.put(id, new WeakReference<View>(view));
                    }
                }
                ((TextView) view.findViewById(R.id.statusView)).setText(status);
            }
        };
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

    public void deleteSelected() {
        final long[] ids = getListView().getCheckedItemIds();

        // Stop any connections first
        final Intent intent = new Intent(getActivity(), BluetoothLoggerService.class);
        getActivity().bindService(intent, new ServiceConnection() {
            final LongSparseArray<Integer> pendingIDs = new LongSparseArray<Integer>();

            private void deleteRecords() {
                new AsyncTask<Object, Integer, Object>() {
                    @Override
                    protected Object doInBackground(Object... objects) {
                        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                        for (long id : ids)
                            ops.add(ContentProviderOperation.newDelete(Uri.withAppendedPath(CaptureProvider.CAPTURES_URI, Long.toString(id))).build());
                        try {
                            getActivity().getContentResolver().applyBatch(CaptureProvider.AUTHORITY, ops);
                        } catch (RemoteException e) {
                            throw new RuntimeException(e);
                        } catch (OperationApplicationException e) {
                            Log.w("BaseActivity", e.getLocalizedMessage()); // TODO?
                        }
                        return null;
                    }

                    @Override
                    protected void onPostExecute(Object o) {
                        super.onPostExecute(o);
                        getLoaderManager().restartLoader(0, null, loaderCallbacks);
                    }
                }.execute(new Object[0]);
            }

            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                for (long id : ids)
                    pendingIDs.put(id, 1);
                BluetoothLoggerService.Binder service = ((BluetoothLoggerService.Binder) iBinder);
                service.register(new Handler(getActivity().getMainLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.what == BluetoothLoggerService.MESSAGE_UPDATE && msg.arg2 == DeviceConnection.State.CLOSED.ordinal())
                            pendingIDs.remove(((BluetoothLoggerService.Device) msg.obj).getDbid());
                        if (pendingIDs.size() == 0)
                            deleteRecords();
                    }
                });
                for (long id : ids) {
                    BluetoothLoggerService.Device device = service.getDevice(id);
                    if (device != null)
                        service.disconnect(device);
                    else
                        pendingIDs.delete(id);
                }
                if (pendingIDs.size() == 0) // all of the captures weren't running
                    deleteRecords();
                getActivity().unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        }, Context.BIND_AUTO_CREATE);
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
