package org.carplaytocarlife.iccoa;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the CarPlay negotiation when the car's Bluetooth links up.
 *
 * The reference app does the same thing from an exported receiver on
 * ACL_CONNECTED; without it the phone only publishes its SDP records and waits.
 */
public final class CarplayBluetoothReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        SinkService service = SinkService.instance();
        if (service != null) {
            service.onCarConnected(device.getAddress());
        } else {
            Logs.add("car connected over Bluetooth but the sink service is not running");
        }
    }
}
