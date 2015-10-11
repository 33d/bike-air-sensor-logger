package au.id.dsp.bikeairsensorlogger.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import au.id.dsp.bikeairsensorlogger.activity.DeviceControlActivity;

/**
 * Created by damien on 6/10/15.
 */
public class DeviceConnection extends Thread {

    /** The serial port profile UUID */
    private static final UUID uuid = new UUID(0x0000110100001000l, 0x800000805F9B34FBl);

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_DEVICE_NAME_CHANGE = 2;
    public static final int MESSAGE_READ = 3;

    public enum State { IDLE, CONNECTING, CONNECTED, CLOSING, ERROR, CLOSED};

    private final Handler handler;
    private final BluetoothDevice device;
    private BluetoothSocket socket;
    private State state;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public DeviceConnection(String address, Handler handler) {
        super("DeviceConnector " + address);
        this.handler = handler;
        this.device = BluetoothAdapter.getDefaultAdapter()
                .getRemoteDevice(address);
        setState(State.IDLE, null);
    }

    private synchronized void setState(State state, Throwable error) {
        this.state = state;
        handler.obtainMessage(MESSAGE_STATE_CHANGE, state.ordinal(), 0, error).sendToTarget();
    }

    public synchronized State getConnectionState() {
        return state;
    }

    public String getDeviceName() {
        return device.getName();
    }

    public String getDeviceAddress() {
        return device.getAddress();
    }

    public void cancel() {
        try {
            socket.close(); // wake up anything blocked on read()
        } catch (IOException e) {
            // I'll hope that the thread gets its own exception
        }
        running.set(false);
    }

    @Override
    public void run() {
        handler.obtainMessage(MESSAGE_DEVICE_NAME_CHANGE, 0, 0, getDeviceName());

        Throwable error = null;
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(uuid);
            setState(State.CONNECTING, null);
            socket.connect();
            setState(State.CONNECTED, null);

            InputStream in = socket.getInputStream();
            StringBuilder message = new StringBuilder();
            byte[] buffer = new byte[512];
            while (running.get()) {
                int bytesRead = in.read(buffer);
                // ISO-8859-1 contains all 256 bytes
                message.append(new String(buffer, 0, bytesRead, "ISO-8859-1"));

                int end;
                while ((end = message.indexOf("\n")) != -1) {
                    handler.obtainMessage(MESSAGE_READ, bytesRead, 0, message.toString()).sendToTarget();
                    message.delete(0, end + 1);
                }
            }

            setState(State.CLOSING, null);
        } catch (IOException e) {
            error = e;
        } finally {
            try {
                socket.close();
                setState(State.CLOSED, null);
            } catch (IOException e) {
                setState(State.ERROR, error == null ? e : error);
            }
        }
    }
}
