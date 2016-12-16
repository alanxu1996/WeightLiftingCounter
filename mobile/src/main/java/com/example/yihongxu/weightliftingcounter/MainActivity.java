package com.example.yihongxu.weightliftingcounter;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Button;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import android.content.Intent;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The main activity for the Weight Lifting Counter application on mobile side. It consists of
 * a part for connecting with the watch using the GoogleAPI and another part for connecting with
 * the robot using Bluetooth.
 */

public class MainActivity extends AppCompatActivity implements
        DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
    private static final String COUNT_KEY = "com.yihongxu.ropeskippingcounter.count";
    private int mCount = 0;
    private GoogleApiClient mGoogleApiClient;
    private TextView mTextView;
    private Button mButton;
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private OutputStream outStream = null;
    private boolean mBTConnected = false;
    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // MAC-address of Bluetooth module
    private static String address = "20:16:03:10:20:62";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mTextView = (TextView) this.findViewById(R.id.text1);
        mTextView.setTextSize(160);
        mButton = (Button) this.findViewById(R.id.btn1);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectBluetooth();
            }
        });
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    private void connectBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter != null) {
            if (!btAdapter.isEnabled()) {
                //Prompt user to turn on Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }
        BluetoothDevice device = btAdapter.getRemoteDevice(address);
        try {
            btSocket = createBluetoothSocket(device);
        } catch (IOException e1) {
            mButton.setText("Socket Creation Failed");
        }
        btAdapter.cancelDiscovery();
        try {
            btSocket.connect();
            mBTConnected = true;
        } catch (IOException e) {
            mBTConnected = false;
            mButton.setText("Connection Failed");
            try {
                btSocket.close();
            } catch (IOException e2) {
                mButton.setText("Cannot Close Socket");
            }
        }
        if (mBTConnected) {
            try {
                outStream = btSocket.getOutputStream();
                if (outStream != null) {
                    mButton.setText("Connection Established");
                } else {
                    mBTConnected = false;
                    mButton.setText("Output Stream Failed");

                }
            } catch (IOException e) {
                mBTConnected = false;
                mButton.setText("Output Stream Failed");
            }
        }
    }

    private void sendData(String message) {
        byte[] msgBuffer = message.getBytes();
        try {
            outStream.write(msgBuffer);
        } catch (IOException e) {
            mButton.setText("Writing Failed");
        }
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if(Build.VERSION.SDK_INT >= 10){
            try {
                final Method  m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[] { UUID.class });
                return (BluetoothSocket) m.invoke(device, MY_UUID);
            } catch (Exception e) {
                mButton.setText("Insecure Rfcomm Socket Creation Failed");
            }
        }
        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.DataApi.addListener(mGoogleApiClient, this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Wearable.DataApi.removeListener(mGoogleApiClient, this);
        mGoogleApiClient.disconnect();
        if (outStream != null) {
            try {
                outStream.flush();
            } catch (IOException e) {}
        }
        try {
            btSocket.close();
        } catch (IOException e2) {}
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                // DataItem changed
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo("/count") == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    updateCount(dataMap.getInt(COUNT_KEY));
                }
            } else if (event.getType() == DataEvent.TYPE_DELETED) {
                // DataItem deleted
            }
        }
    }

    private void updateCount(int count) {
        mTextView.setText(Integer.toString(count));
        if (mBTConnected) {
            mButton.setText("Sending Data");
            sendData(Integer.toString(4 * (count - mCount)));
        }
        mCount = count;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (null != mGoogleApiClient && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    @Override
    public void onConnectionSuspended(int cause) { }

    @Override
    public void onConnectionFailed(ConnectionResult result) { }
}
