package com.example.daniel.bike_device;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.charset.Charset;

public class LaunchActivity extends AppCompatActivity implements LocationListener, CompoundButton.OnCheckedChangeListener, SensorEventListener, NfcAdapter.CreateNdefMessageCallback {

    //Bluetooth Variables
    byte[] buffer = new byte[1];
    private BluetoothAdapter mBluetoothAdapter = null;
    private static BluetoothService mSerialService = null;
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    private static final int REQUEST_CONNECT_DEVICE = 1; //onActivityResult ID number for connecting devices in the DeviceListActivity class.
    private static final int REQUEST_ENABLE_BT = 2; //onActivityResult ID number for enabling bluetooth -> class not created by WiMB.

    //Menu Variables
    private MenuItem mMenuItemStartStopRecording;
    private MenuItem mMenuItemConnect;

    //GPS Variables
    static int gpsSwitchState;
    protected LocationManager locationManager;
    protected LocationListener locationListener;
    protected Context context;

    //Accelerometer Variables
    private SensorManager mSensorManager;
    private Sensor mSensor;
    private long startTime, thresholdStartTime = 0;
    private float last_x, last_y, last_z = 0;
    private static final float SHAKE_SENSOR_THRESHOLD = 10; //
    private static final int TIME_SENSOR_THRESHOLD = 4000; //milliseconds
    private static final int COUNTER_SENSOR_THRESHOLD = 10; //no unit
    private static final int TIME_BETWEEN_SENSOR_MEASUREMENTS = 200; //milliseconds
    static int sensorSwitchState,movingCounter;

    //THIS IT NEEDED FOR NFC CONTACT.
    //NFC Variables
    NfcAdapter mNfcAdapter;
    EditText nfcTextOut;
    TextView nfcTextIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        //Bluetooth Setup
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mSerialService = new BluetoothService(this, mHandlerBT);

        //Simple Bluetooth Button Setup
        Button sendBut = (Button) findViewById(R.id.sendBut);
        sendBut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CharSequence cs = "q";
                sendText(cs);
            }
        });

        //GPS Setup
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {return; }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, this);
        Switch gpsSwicth = (Switch) findViewById(R.id.gpsSwitch);
        gpsSwicth.setOnCheckedChangeListener(this);
        gpsSwitchState = 0;


        //Sensor Setup
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mSensor , SensorManager.SENSOR_DELAY_NORMAL);
        Switch sensorSwitch = (Switch) findViewById(R.id.motionSensorsSwitch);
        sensorSwitch.setOnCheckedChangeListener(this);
        sensorSwitchState = 0;
        movingCounter = 0;

        //THIS IT NEEDED FOR NFC CONTACT. (or another way to see the NFC in- and output)
        //NFC Setup
        nfcTextOut = (EditText) findViewById(R.id.nfcTextOut);
        nfcTextIn = (TextView) findViewById(R.id.nfcTextIn);
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this); // Check for available NFC Adapter
        if (mNfcAdapter == null) {
            Toast.makeText(this, "NFC is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mNfcAdapter.setNdefPushMessageCallback(this, this); // Register callback

    }

    @Override
    public void onPause(){
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (NfcAdapter.ACTION_NDEF_DISCOVERED.equals(getIntent().getAction())) {  // Check to see that the Activity started due to an Android Beam
            processIntent(getIntent());
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { //Switch buttons state check to turn gps and sensor on and off.
        switch(buttonView.getId()){ //Check the ID of the switch which was changed.
            case R.id.gpsSwitch:
                if(isChecked) {
                    gpsSwitchState = 1; //Turn on GPS when Switch is ON
                } else {
                    gpsSwitchState = 0; //Turn off GPS when Switch if OFF
                }
                break;
            case R.id.motionSensorsSwitch:
                if(isChecked) {
                    sensorSwitchState = 1; //Turn on sensor when Switch is ON
                } else {
                    sensorSwitchState = 0; //Turn off sensor when Switch if OFF
                }
                break;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event){ //When any sensor registers any change this method is call. The method is a requirement from the SensorEventListener class which is implemented to the activity.
        Sensor mySensor = event.sensor;
        if(sensorSwitchState == 1) {                //Checking if the sensor swicth is on. (hint: onCheckedChanged())
            if(startTime == 0) {                    //startTime can either be zero if it is the first time or if it has been reset.
                startTime = System.currentTimeMillis(); //set current time to startTime.
            }else if((System.currentTimeMillis() - startTime) > TIME_BETWEEN_SENSOR_MEASUREMENTS){  //if the difference between now and the startTime is greater than TIME_BETWEEN_SENSOR_MEASUREMENTS continue to store a measurement.
                if (mySensor.getType() == Sensor.TYPE_ACCELEROMETER) {             //Stores x, y and z if the sensor change event is related to the accelerometer.
                    float x = event.values[0];
                    float y = event.values[1];
                    float z = event.values[2];
                    if(last_x == 0 && last_y == 0 && last_z == 0) { //Stores the new axises values in the old axises values if the old values have not been used yet.
                        last_x = x;
                        last_y = y;
                        last_z = z;
                    }else{
                        float threshold_check = Math.abs(x-last_x)+Math.abs(y-last_y)+Math.abs(z-last_z); //Calculate absolute difference between all axises.
                        if (threshold_check > SHAKE_SENSOR_THRESHOLD) { //Check if the absolute difference is higher than the SHAKE_SENSOR_THRESHOLD.
                            if(thresholdStartTime == 0){
                                thresholdStartTime = System.currentTimeMillis(); //start timer when registering initial movement.
                            }
                            movingCounter++; //increase counter each time a high movement is registered.
                        }
                        if((System.currentTimeMillis() - thresholdStartTime > TIME_SENSOR_THRESHOLD)){ //Time is up, since the initial movement.
                            thresholdStartTime = 0;                                                    //Reset timer.
                            if(movingCounter > COUNTER_SENSOR_THRESHOLD) {                              //Check if the amount of counters is higher than COUNTER_SENSOR_THRESHOLD.
                                Log.d("Counter_Sensor", "Success!");                                    //Send code to Arduino for starting Siren.
                                CharSequence SensorChar = "q";
                                sendText(SensorChar);
                            }
                            movingCounter = 0;
                        }
                        last_x = x;             //Stores the new axises values in the old axises values.
                        last_y = y;
                        last_z = z;
                    }
                }
                startTime = 0; //reset startTime.
            }
        }
    }


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { //Method required, since SensorEventListener is implemented into the activity. However, no action is required here.
    }

    private void sendText(CharSequence text) { //Method used for sending text to Arduino. This method will initially take the text and split it into indivudual letter and call the mapAndSend() method for each letter.
        int n = text.length();
        try {
            for(int i = 0; i < n; i++) {
                char c = text.charAt(i);
                mapAndSend(c);
            }
        } catch (IOException e) {
        }
    }

    private void mapAndSend(int c) throws IOException { //Method used for converting the "char" input from the sendText() method into its equivalent "int" value and afterwards into a byte value. The byte is put into a byte array and used as input for the send() method.
        byte[] mBuffer = new byte[1];
        mBuffer[0] = (byte)c;
        send(mBuffer);
    }

    public void send(byte[] out) { //Method used to send byte arrays to Arduino using the object of BluetoothService class.
        if ( out.length > 0 ) {
            mSerialService.write( out );
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {  //Creates the option menu for connecting to bluetooth devices in the application.
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        mMenuItemConnect = menu.getItem(0);
        mMenuItemStartStopRecording = menu.getItem(0);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {  //Method called when an item on the menu is clicked. Only one option in this case.
        switch (item.getItemId()) {
            case R.id.connect:
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                return true;
        }
        return false;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) { //Method called when another activity returns to this activity with a result.
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE: // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) { // Get the device MAC address
                    String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS); // Get the BluetoothDevice object
                    BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address); // Attempt to connect to the device
                    mSerialService.connect(device);
                }
                break;
            case REQUEST_ENABLE_BT:
                if (getConnectionState() == BluetoothService.STATE_NONE) { // Launch the DeviceListActivity to see devices and do scan
                    Intent serverIntent = new Intent(this, DeviceListActivity.class);
                    startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                }
                if (getConnectionState() == BluetoothService.STATE_CONNECTED) {
                    mSerialService.stop();
                    mSerialService.start();
                }
                break;
        }
    }

    public int getConnectionState() { //Method used for getting the current state of the bluetooth connection.
        return mSerialService.getState();
    }

    private final Handler mHandlerBT = new Handler() {
        @Override
        public void handleMessage(Message msg) { // The Handler that gets information back from the BluetoothService
            switch (msg.arg1) {
                case BluetoothService.STATE_CONNECTED:
                    Toast.makeText(getBaseContext(),R.string.title_connected_to,Toast.LENGTH_LONG).show();
                    break;
                case BluetoothService.STATE_CONNECTING:
                    Toast.makeText(getBaseContext(),R.string.title_connecting,Toast.LENGTH_LONG).show();
                    break;
            }
        }
    };

    @Override
    public void onLocationChanged(Location location) { //Method call every time a location change is registered.
        if(gpsSwitchState == 1) {                       //Check if the GPS swicth is on.
            CharSequence LatAndLon = " - Latitude: " + Double.toString(location.getLatitude()) + " - Longitude: " + Double.toString(location.getLongitude()); //Send the location to Arduino.
            sendText(LatAndLon);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    @Override
    public void onProviderEnabled(String provider) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    @Override
    public void onProviderDisabled(String provider) { //Method required, since LocationListener is implemented into the activity. However, no action is required here.
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    @Override
    public NdefMessage createNdefMessage(NfcEvent event) {
        String text = (nfcTextOut.getText().toString());
        NdefMessage msg = new NdefMessage(new NdefRecord[] { createMimeRecord("application/com.example.daniel.bluetoothtestv20", text.getBytes()),NdefRecord.createApplicationRecord("com.example.daniel.bluetoothtestv20")});
        return msg;
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    // Creates a custom MIME type encapsulated in an NDEF record // @param mimeType
    public NdefRecord createMimeRecord(String mimeType, byte[] payload) {
        byte[] mimeBytes = mimeType.getBytes(Charset.forName("US-ASCII"));
        NdefRecord mimeRecord = new NdefRecord(NdefRecord.TNF_MIME_MEDIA, mimeBytes, new byte[0], payload);
        return mimeRecord;
    }

    //THIS IT NEEDED FOR NFC CONTACT.
    //Parses the NDEF Message from the intent and prints to the TextView
    void processIntent(Intent intent) {
        Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES); // only one message sent during the beam
        NdefMessage msg = (NdefMessage) rawMsgs[0];
        nfcTextIn.setText("BikeID: " + new String(msg.getRecords()[0].getPayload())+"\n"+"Encrypted Challenge: [....]"); // record 0 contains the MIME type.
    }
}
