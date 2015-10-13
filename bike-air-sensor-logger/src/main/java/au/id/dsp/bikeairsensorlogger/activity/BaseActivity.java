package au.id.dsp.bikeairsensorlogger.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;

import au.id.dsp.bikeairsensorlogger.R;
import au.id.dsp.bikeairsensorlogger.Utils;
import au.id.dsp.bikeairsensorlogger.bluetooth.BluetoothLoggerService;
import au.id.dsp.bikeairsensorlogger.bluetooth.DeviceListActivity;

/**
 * Общий базовый класс. Инициализация BT-адаптера
 * Created by sash0k on 09.12.13.
 */
public class BaseActivity extends SherlockFragmentActivity {
    private static final String TAG = "bikeairsensorlogger";

    // Intent request codes
    static final int REQUEST_CONNECT_DEVICE = 1;
    static final int REQUEST_ENABLE_BT = 2;

    private BluetoothLoggerService service;
    BluetoothAdapter btAdapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            final String no_bluetooth = getString(R.string.no_bt_support);
            showAlertDialog(no_bluetooth);
            Utils.log(no_bluetooth);
        }

        setContentView(R.layout.base_layout);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getSupportMenuInflater().inflate(R.menu.base_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_start_capture:
                Intent serverIntent = new Intent(this, DeviceListActivity.class);
                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    String address = data.getStringExtra(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                    startLogging(address);
                }
                break;
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void startLogging(final String address) {
        final Intent intent = new Intent(this, BluetoothLoggerService.class);
        startService(intent);
        bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                ((BluetoothLoggerService.Binder) iBinder).connect(BaseActivity.this, address);
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) { }
        }, 0);
    }

    /**
     * Показывает диалоговое окно с предупреждением.
     * TODO: При переконфигурациях будет теряться
     *
     * @param message - сообщение
     */
    void showAlertDialog(String message) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setTitle(getString(R.string.app_name));
        alertDialogBuilder.setMessage(message);
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }
    // ==========================================================================
}
