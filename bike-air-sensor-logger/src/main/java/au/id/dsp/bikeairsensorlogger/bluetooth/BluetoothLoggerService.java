package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
    private static final AtomicInteger nextNotificationId = new AtomicInteger(1);

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
                for (DeviceHandler deviceHandler : handlers.values())
                    deviceHandler.sendState(handler);
            }
        }

        public void disconnect(String address) {
            BluetoothLoggerService.this.disconnect(address);
        }

        public boolean isConnected(String address) {
            DeviceHandler h = null;
            synchronized(BluetoothLoggerService.this) {
                h = handlers.get(address);
            }
            return h == null ? false : !notActiveStates.contains(h.getState().ordinal());
        }

        public Device getDevice(String address) {
            return handlers.get(address).descriptor;
        }
    };
    private final Binder clientBinder = new Binder();
    private LogDatabase db;

    /** Details of a remote device.  These methods are thread safe. */
    public abstract static class Device {
        private final String address;
        private final String name;
        private long dbid;
        private final AtomicReference<Throwable> lastError = new AtomicReference<>();

        private Device(String address, String name) {
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
            this.descriptor = new Device(device.getAddress(), device.getName()) { // yuck
                @Override
                public DeviceConnection.State getState() {
                    return connection.getConnectionState();
                }
            };
            capture = db.createCapture(descriptor.getAddress(), descriptor.getName());
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
                    capture.write(Double.NaN, Double.NaN, Double.NaN, System.currentTimeMillis(), (String) msg.obj);
                    break;
            }
        }
    }

    private final Map<String, DeviceHandler> handlers = new HashMap<String, DeviceHandler>();

    @SuppressWarnings("deprecation") // (NotificationBuilder is not available in API 10)
    private synchronized void connect(Context context, String address) {
        Notification notification = new Notification(R.drawable.ic_action_bluetooth,
                "Starting logger", System.currentTimeMillis());
        notification.setLatestEventInfo(context, "Bluetooth logger running", "", null);
        startForeground(nextNotificationId.getAndIncrement(), notification);
        handlers.put(address, new DeviceHandler(address).connect());
    }

    /** Initiates disconnection.  The connection isn't closed until the
     * {@link au.id.dsp.bikeairsensorlogger.bluetooth.DeviceConnection.State#CLOSED} state is sent.
     */
    private void disconnect(String address) {
        DeviceHandler handler = null;
        synchronized (this) {
            handler = handlers.get(address);
        }
        handler.disconnect();
    }

    private synchronized void handlerFinished(DeviceHandler handler) {
        handlers.remove(handler.descriptor.getAddress());
        if (handlers.isEmpty()) {
            stopForeground(true);
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
