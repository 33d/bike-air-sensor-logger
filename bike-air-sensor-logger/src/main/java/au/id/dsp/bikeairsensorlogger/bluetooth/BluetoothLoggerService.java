package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.service.notification.NotificationListenerService;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import au.id.dsp.bikeairsensorlogger.R;

/**
 * Created by damien on 5/10/15.
 */
public class BluetoothLoggerService extends Service {
    private static final String TAG = "BluetoothLoggerService";

    public static final int MESSAGE_UPDATE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_CONNECT = 3;
    public static final int MESSAGE_DISCONNECT = 4;
    public static final String EXTRA_MESSENGER = "au.id.dsp.bikeairsensorlogger.bluetooth.BluetoothLoggerService.EXTRA_MESSENGER";
    public static final String KEY_ERROR = "au.id.dsp.bikeairsensorlogger.bluetooth.BluetoothLoggerService.ERROR";
    private static volatile int nextHandlerId = 0;

    /** Which states initiate a reconnection */
    private static final Collection<Integer> notActiveStates = Arrays.asList(
            DeviceConnection.State.IDLE.ordinal(),
            DeviceConnection.State.CLOSED.ordinal(),
            DeviceConnection.State.ERROR.ordinal());

    private static final int MESSAGE_RETRYCONNECT = 50000;

    private static final Collection<WeakReference<Handler>> clients = new ArrayList<WeakReference<Handler>>();

    public class Binder extends android.os.Binder {
        public void connect(Context context, String address) {
            BluetoothLoggerService.this.connect(context, address);
        }

        public void register(Handler handler) {
            synchronized (BluetoothLoggerService.this) {
                clients.add(new WeakReference<Handler>(handler));
                for (int i = 0 ; i < handlers.size(); i++)
                    handlers.valueAt(i).sendState(handler);
            }
        }

        public void disconnect(Device device) {
            BluetoothLoggerService.this.disconnect(device);
        }

        public boolean isConnected(Device device) {
            DeviceHandler h = null;
            synchronized(BluetoothLoggerService.this) {
                h = handlers.get(device.id);
            }
            return h == null ? false : !notActiveStates.contains(h.getState().ordinal());
        }

        public Device getDevice(long dbid) {
            DeviceHandler h = handlersByDbId.get(dbid);
            return h == null ? null : h.descriptor;
        }
    };
    private final Binder clientBinder = new Binder();
    private LogDatabase db;
    private LocationManager locationManager;
    private final AtomicReference<Location> lastLocation = new AtomicReference<>();
    private class GPSListener implements LocationListener {
        private final Notification noGPSNotification = new NotificationCompat.Builder(BluetoothLoggerService.this)
                .setContentTitle("GPS is turned off")
                .setSmallIcon(R.drawable.ic_gps_not_fixed_white_24dp)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        @Override
        public void onLocationChanged(Location location) {
            lastLocation.set(location);
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) { }

        @Override
        public void onProviderEnabled(String s) {
            notificationManager.cancel(1);
        }

        @Override
        public void onProviderDisabled(String s) {
            notificationManager.notify(1, noGPSNotification);
        }
    };
    private LocationListener locationListener;

    /** Details of a remote device.  These methods are thread safe. */
    public abstract static class Device {
        private final int id;
        private final String address;
        private final String name;
        // Having this writable is gross; Device objects should only be
        // returned once the database record is created
        private long dbid;
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();

        private Device(int id, String address, String name) {
            this.id = id;
            this.address = address;
            this.name = name;
        }

        public String getAddress() {
            return address;
        }

        public String getName() {
            return name;
        }

        public long getDbid() {
            return dbid;
        }

        public Throwable getLastError() {
            return lastError.get();
        }

        public abstract DeviceConnection.State getState();
    }

    private final class DeviceHandler extends Handler {
        private LogDatabase.WritableCapture capture;
        private final int id;
        private final Device descriptor;
        private DeviceConnection connection;
        private int messageCount;

        public DeviceHandler(String address) {
            this.id = nextHandlerId++;
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
            this.descriptor = new Device(id, device.getAddress(), device.getName()) {
                @Override  // yuck
                public DeviceConnection.State getState() {
                    return connection.getConnectionState();
                }
            };
            // TODO: This should happen on another thread
            capture = db.createCapture(descriptor.getAddress(), descriptor.getName());
            handlersByDbId.put(capture.getKey(), this);
            descriptor.dbid = capture.getKey();
        }

        public DeviceHandler connect() {
            if (connection != null && connection.isAlive())
                throw new IllegalStateException();
            connection = new DeviceConnection(descriptor.getAddress(), this);
            connection.start();
            sendToClients(MESSAGE_UPDATE, connection.getConnectionState().ordinal(), descriptor);
            return this;
        }

        public void disconnect() {
            connection.cancel();
            try {
                capture.close();
            } catch (IOException e) {
                Log.w(TAG, "Can't close database capture", e);
            }
        }

        private void sendToClients(int what, int arg2, Object o) {
            synchronized (BluetoothLoggerService.this) {
                for (Iterator<WeakReference<Handler>> it = clients.iterator(); it.hasNext(); ) {
                    Handler h = it.next().get();
                    if (h == null)
                        it.remove();
                    else
                        h.obtainMessage(what, id, arg2, o).sendToTarget();
                }
            }
        }

        private void sendState(Handler handler) {
            handler.obtainMessage(MESSAGE_UPDATE, id, connection.getConnectionState().ordinal(), descriptor).sendToTarget();
        }

        private DeviceConnection.State getState() {
            return connection.getConnectionState();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_RETRYCONNECT:
                    connect();
                    break;
                case DeviceConnection.MESSAGE_STATE_CHANGE:
                    descriptor.lastError.set((Throwable) msg.obj);
                    sendToClients(MESSAGE_UPDATE, msg.arg1, descriptor);
                    if (DeviceConnection.State.ERROR.ordinal() == msg.arg1)
                        sendMessageDelayed(obtainMessage(MESSAGE_RETRYCONNECT), 10000);
                    else if (DeviceConnection.State.CLOSED.ordinal() == msg.arg1)
                        handlerFinished(this);
                    break;
                case DeviceConnection.MESSAGE_READ:
                    ++messageCount;
                    sendToClients(MESSAGE_READ, messageCount, (String) msg.obj);
                    Location location = lastLocation.get();
                    long now = System.currentTimeMillis();
                    if (location == null || location.getTime() < now - 10000)
                        capture.write(
                                Double.NaN, Double.NaN, Double.NaN, now, (String) msg.obj);
                    else
                        capture.write(
                                location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                                now, (String) msg.obj);
                    break;
            }
        }
    }

    private final SparseArray<DeviceHandler> handlers = new SparseArray<DeviceHandler>();
    private final Map<Long, DeviceHandler> handlersByDbId = Collections.synchronizedMap(new HashMap<Long, DeviceHandler>());

    @SuppressWarnings("deprecation") // (NotificationBuilder is not available in API 10)
    private synchronized void connect(Context context, String address) {
        Notification notification = new Notification(R.drawable.ic_action_bluetooth,
                "Starting logger", System.currentTimeMillis());
        notification.setLatestEventInfo(context, "Bluetooth logger running", "", null);
        startForeground(2, notification);
        DeviceHandler handler = new DeviceHandler(address);
        handlers.put(handler.id, handler);
        handler.connect();
    }

    public void disconnect(Device device) {
        handlers.get(device.id).disconnect();
    }

    private synchronized void handlerFinished(DeviceHandler handler) {
        handlers.remove(handler.id);
        handlersByDbId.remove(handler.descriptor.dbid);
        if (handlers.size() == 0) {
            stopForeground(true);
            locationManager.removeUpdates(locationListener);
            locationListener = null;
            try {
                db.close();
            } catch (IOException e) {
                Log.w("BluetoothLoggerService", "Database didn't close", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        db = new LogDatabase(this);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationListener = new GPSListener();
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationListener.onProviderDisabled("");
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                10000,          // 10-second interval.
                10,             // 10 meters.
                locationListener);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return clientBinder;
    }

    @Override
    public synchronized boolean onUnbind(Intent intent) {
        clients.remove(intent);
        return false;
    }
}
