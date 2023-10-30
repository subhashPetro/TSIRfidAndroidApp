package com.petroit.tsirfidandroidapp.rfid.inventory;

import static com.uk.tsl.rfid.DeviceListActivity.EXTRA_DEVICE_ACTION;
import static com.uk.tsl.rfid.DeviceListActivity.EXTRA_DEVICE_INDEX;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.petroit.tsirfidandroidapp.R;
import com.petroit.tsirfidandroidapp.databinding.ActivityInventoryBinding;
import com.uk.tsl.rfid.DeviceListActivity;
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander;
import com.uk.tsl.rfid.asciiprotocol.BuildConfig;
import com.uk.tsl.rfid.asciiprotocol.device.ConnectionState;
import com.uk.tsl.rfid.asciiprotocol.device.IAsciiTransport;
import com.uk.tsl.rfid.asciiprotocol.device.ObservableReaderList;
import com.uk.tsl.rfid.asciiprotocol.device.Reader;
import com.uk.tsl.rfid.asciiprotocol.device.ReaderManager;
import com.uk.tsl.rfid.asciiprotocol.device.TransportType;
import com.uk.tsl.rfid.asciiprotocol.responders.LoggerResponder;
import com.uk.tsl.utils.Observable;

public class InventoryActivity extends AppCompatActivity
{
    // Debugging
    private static final String TAG = "InventoryActivity";
    private static final boolean D = BuildConfig.DEBUG;

    private AppBarConfiguration appBarConfiguration;
    private ActivityInventoryBinding binding;

    // The Reader currently in use
    private Reader mReader = null;
    private Reader mLastUserDisconnectedReader = null;
    private boolean mIsSelectingReader = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Ensure the shared instance of AsciiCommander exists
        AsciiCommander.createSharedInstance(getApplicationContext());

        final AsciiCommander commander = getCommander();

        // Ensure that all existing responders are removed
        commander.clearResponders();

        // Add the LoggerResponder - this simply echoes all lines received from the reader to the log
        // and passes the line onto the next responder
        // This is ADDED FIRST so that no other responder can consume received lines before they are logged.
        commander.addResponder(new LoggerResponder());

        // Add responder to enable the synchronous commands
        commander.addSynchronousResponder();

        // Configure the ReaderManager when necessary
        ReaderManager.create(getApplicationContext());

        // Add observers for changes
        ReaderManager.sharedInstance().getReaderList().readerAddedEvent().addObserver(mAddedObserver);
        ReaderManager.sharedInstance().getReaderList().readerUpdatedEvent().addObserver(mUpdatedObserver);
        ReaderManager.sharedInstance().getReaderList().readerRemovedEvent().addObserver(mRemovedObserver);


        // Now build the UI
        binding = ActivityInventoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_inventory);
        appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
    }


    @Override
    protected void onDestroy()
    {
        super.onDestroy();

        // Remove observers for changes
        ReaderManager.sharedInstance().getReaderList().readerAddedEvent().removeObserver(mAddedObserver);
        ReaderManager.sharedInstance().getReaderList().readerUpdatedEvent().removeObserver(mUpdatedObserver);
        ReaderManager.sharedInstance().getReaderList().readerRemovedEvent().removeObserver(mRemovedObserver);
    }


    @Override
    protected void onStart()
    {
        super.onStart();
        checkForBluetoothPermission();
    }


    //----------------------------------------------------------------------------------------------
    // Pause & Resume life cycle
    //----------------------------------------------------------------------------------------------

    @Override
    public synchronized void onPause() {
        super.onPause();

        // Stop observing events from the AsciiCommander
        getCommander().stateChangedEvent().removeObserver(mConnectionStateObserver);

        // Disconnect from the reader to allow other Apps to use it
        // unless pausing when USB device attached or using the DeviceListActivity to select a Reader
        if( !mIsSelectingReader && !ReaderManager.sharedInstance().didCauseOnPause() && mReader != null )
        {
            mReader.disconnect();
        }

        ReaderManager.sharedInstance().onPause();

    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        // Observe events from the AsciiCommander
        getCommander().stateChangedEvent().addObserver(mConnectionStateObserver);

        // Remember if the pause/resume was caused by ReaderManager - this will be cleared when ReaderManager.onResume() is called
        boolean readerManagerDidCauseOnPause = ReaderManager.sharedInstance().didCauseOnPause();

        // The ReaderManager needs to know about Activity lifecycle changes
        ReaderManager.sharedInstance().onResume();

        // The Activity may start with a reader already connected (perhaps by another App)
        // Update the ReaderList which will add any unknown reader, firing events appropriately
        ReaderManager.sharedInstance().updateList();

        // Locate a Reader to use when necessary
        AutoSelectReader(!readerManagerDidCauseOnPause);

        mIsSelectingReader = false;
    }


    //----------------------------------------------------------------------------------------------
    // ReaderList Observers
    //----------------------------------------------------------------------------------------------
    Observable.Observer<Reader> mAddedObserver = new Observable.Observer<Reader>()
    {
        @Override
        public void update(Observable<? extends Reader> observable, Reader reader)
        {
            // See if this newly added Reader should be used
            AutoSelectReader(true);
        }
    };

    Observable.Observer<Reader> mUpdatedObserver = new Observable.Observer<Reader>()
    {
        @Override
        public void update(Observable<? extends Reader> observable, Reader reader)
        {
            // Is this a change to the last actively disconnected reader
            if( reader == mLastUserDisconnectedReader )
            {
                // Things have changed since it was actively disconnected so
                // treat it as new
                mLastUserDisconnectedReader = null;
            }

            // Was the current Reader disconnected i.e. the connected transport went away or disconnected
            if( reader == mReader && !reader.isConnected() )
            {
                // No longer using this reader
                mReader = null;

                // Stop using the old Reader
                getCommander().setReader(mReader);
            }
            else
            {
                // See if this updated Reader should be used
                // e.g. the Reader's USB transport connected
                AutoSelectReader(true);
            }
        }
    };

    Observable.Observer<Reader> mRemovedObserver = new Observable.Observer<Reader>()
    {
        @Override
        public void update(Observable<? extends Reader> observable, Reader reader)
        {
            // Is this a change to the last actively disconnected reader
            if( reader == mLastUserDisconnectedReader )
            {
                // Things have changed since it was actively disconnected so
                // treat it as new
                mLastUserDisconnectedReader = null;
            }

            // Was the current Reader removed
            if( reader == mReader)
            {
                mReader = null;

                // Stop using the old Reader
                getCommander().setReader(mReader);
            }
        }
    };


    private void AutoSelectReader(boolean attemptReconnect)
    {
        ObservableReaderList readerList = ReaderManager.sharedInstance().getReaderList();
        Reader usbReader = null;
        if( readerList.list().size() >= 1)
        {
            // Currently only support a single USB connected device so we can safely take the
            // first CONNECTED reader if there is one
            for (Reader reader : readerList.list())
            {
                if (reader.hasTransportOfType(TransportType.USB))
                {
                    usbReader = reader;
                    break;
                }
            }
        }

        if( mReader == null )
        {
            if( usbReader != null && usbReader != mLastUserDisconnectedReader)
            {
                // Use the Reader found, if any
                mReader = usbReader;
                getCommander().setReader(mReader);
            }
        }
        else
        {
            // If already connected to a Reader by anything other than USB then
            // switch to the USB Reader
            IAsciiTransport activeTransport = mReader.getActiveTransport();
            if ( activeTransport != null && activeTransport.type() != TransportType.USB && usbReader != null)
            {
                mReader.disconnect();

                mReader = usbReader;

                // Use the Reader found, if any
                getCommander().setReader(mReader);
            }
        }

        // Reconnect to the chosen Reader
        if( mReader != null
                && !mReader.isConnecting()
                && (mReader.getActiveTransport()== null || mReader.getActiveTransport().connectionStatus().value() == ConnectionState.DISCONNECTED))
        {
            // Attempt to reconnect on the last used transport unless the ReaderManager is cause of OnPause (USB device connecting)
            if( attemptReconnect )
            {
                if( mReader.allowMultipleTransports() || mReader.getLastTransportType() == null )
                {
                    // Reader allows multiple transports or has not yet been connected so connect to it over any available transport
                    mReader.connect();
                }
                else
                {
                    // Reader supports only a single active transport so connect to it over the transport that was last in use
                    mReader.connect(mReader.getLastTransportType());
                }
            }
        }
    }

    //----------------------------------------------------------------------------------------------
    // AsciiCommander message handling
    //----------------------------------------------------------------------------------------------

    //
    // Handle the connection state change events from the AsciiCommander
    //
    private final Observable.Observer<String> mConnectionStateObserver = (observable, reason) ->
    {
        if (D) { Log.d(getClass().getName(), "AsciiCommander state changed - isConnected: " + getCommander().isConnected()); }

        Toast.makeText(this.getApplicationContext(), reason, Toast.LENGTH_SHORT).show();

        displayReaderState();
        if(getCommander().getConnectionState() == ConnectionState.DISCONNECTED)
        {
            // A manual disconnect will have cleared mReader
            if( mReader != null )
            {
                // See if this is from a failed connection attempt
                if (!mReader.wasLastConnectSuccessful())
                {
                    // Unable to connect so have to choose reader again
                    mReader = null;
                }
            }
        }
    };


    //----------------------------------------------------------------------------------------------
    // UI state and display update
    //----------------------------------------------------------------------------------------------

    private void displayReaderState() {

        String connectionMsg = "Reader: ";
        switch( getCommander().getConnectionState())
        {
            case CONNECTED:
                connectionMsg += getCommander().getConnectedDeviceName();
                break;
            case CONNECTING:
                connectionMsg += "Connecting...";
                break;
            default:
                connectionMsg += "Disconnected";
        }
        binding.toolbar.setTitle(connectionMsg);
    }


    //----------------------------------------------------------------------------------------------
    // Menu
    //----------------------------------------------------------------------------------------------


    private MenuItem mConnectMenuItem;
    private MenuItem mDisconnectMenuItem;

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        // Inflate the menu; this adds items to the action bar if it is present.
        //getMenuInflater().inflate(R.menu.menu_configure_barcode, menu);
        getMenuInflater().inflate(R.menu.reader_menu, menu);

        mConnectMenuItem = menu.findItem(R.id.connect_reader_menu_item);
        mDisconnectMenuItem= menu.findItem(R.id.disconnect_reader_menu_item);

        return true;
    }

    /**
     * Prepare the menu options
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isConnecting = getCommander().getConnectionState() == ConnectionState.CONNECTING;
        boolean isConnected = getCommander().isConnected();

        mDisconnectMenuItem.setEnabled(isConnected);

        mConnectMenuItem.setEnabled(true);
        mConnectMenuItem.setTitle( (mReader != null && mReader.isConnected() ? R.string.change_reader_menu_item_text : R.string.connect_reader_menu_item_text));

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings)
        {
            return true;
        }
        else if (id == R.id.connect_reader_menu_item)
        {
            // Launch the DeviceListActivity to see available Readers
            mIsSelectingReader = true;
            int index = -1;
            if( mReader != null )
            {
                index = ReaderManager.sharedInstance().getReaderList().list().indexOf(mReader);
            }
            Intent selectIntent = new Intent(this, DeviceListActivity.class);
            if( index >= 0 )
            {
                selectIntent.putExtra(EXTRA_DEVICE_INDEX, index);
            }
            startActivityForResult(selectIntent, DeviceListActivity.SELECT_DEVICE_REQUEST);
            return true;
        }
        else if (id == R.id.disconnect_reader_menu_item)
        {
            if( mReader != null )
            {
                mReader.disconnect();
                mReader = null;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp()
    {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_inventory);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }


    /**
     * @return the current AsciiCommander
     */
    protected AsciiCommander getCommander()
    {
        return AsciiCommander.sharedInstance();
    }


    //----------------------------------------------------------------------------------------------
    // Handle Intent results
    //----------------------------------------------------------------------------------------------

    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == DeviceListActivity.SELECT_DEVICE_REQUEST)
        {// When DeviceListActivity returns with a device to connect
            if (resultCode == Activity.RESULT_OK)
            {
                int readerIndex = data.getExtras().getInt(EXTRA_DEVICE_INDEX);
                Reader chosenReader = ReaderManager.sharedInstance().getReaderList().list().get(readerIndex);

                int action = data.getExtras().getInt(EXTRA_DEVICE_ACTION);

                // If already connected to a different reader then disconnect it
                if (mReader != null)
                {
                    if (action == DeviceListActivity.DEVICE_CHANGE || action == DeviceListActivity.DEVICE_DISCONNECT)
                    {
                        mReader.disconnect();
                        if (action == DeviceListActivity.DEVICE_DISCONNECT)
                        {
                            mReader = null;
                        }
                    }
                }

                // Use the Reader found
                if (action == DeviceListActivity.DEVICE_CHANGE || action == DeviceListActivity.DEVICE_CONNECT)
                {
                    mReader = chosenReader;
                    getCommander().setReader(mReader);
                }
            }
        }
    }


    //----------------------------------------------------------------------------------------------
    // Bluetooth permissions checking
    //----------------------------------------------------------------------------------------------
    //
    private TextView mBluetoothPermissionsTextView;


    private void checkForBluetoothPermission()
    {
        // Older permissions are granted at install time
        if (Build.VERSION.SDK_INT < 31) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            binding.bluetoothPermissionsPrompt.setVisibility(View.VISIBLE);
            if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT))
            {
                // In an educational UI, explain to the user why your app requires this
                // permission for a specific feature to behave as expected. In this UI,
                // include a "cancel" or "no thanks" button that allows the user to
                // continue using your app without granting the permission.
                offerBluetoothPermissionRationale();
            }
            else
            {
                requestPermissionLauncher.launch(bluetoothPermissions);
            }
        }
        else
        {
            binding.bluetoothPermissionsPrompt.setVisibility(View.GONE);
        }
    }

    private final String[] bluetoothPermissions = new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};

    void offerBluetoothPermissionRationale()
    {
        // Older permissions are granted at install time
        if (Build.VERSION.SDK_INT < 31) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Permission is required to connect to TSL Readers over Bluetooth")
               .setTitle("Allow Bluetooth?");

        builder.setPositiveButton("Show Permission Dialog", new DialogInterface.OnClickListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.S)
            public void onClick(DialogInterface dialog, int id)
            {
                requestPermissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN});
            }
        });


        AlertDialog dialog = builder.create();
        dialog.show();
    }


    void showBluetoothPermissionDeniedConsequences()
    {
        // Note: When permissions have been denied, this will be invoked everytime checkForBluetoothPermission() is called
        // In your app, we suggest you limit the number of times the User is notified.
        Toast.makeText(this, "This app will not be able to connect to TSL Readers via Bluetooth.", Toast.LENGTH_LONG).show();
    }


    // Register the permissions callback, which handles the user's response to the
    // system permissions dialog. Save the return value, an instance of
    // ActivityResultLauncher, as an instance variable.
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissionsGranted ->
            {
                //boolean allGranted = permissionsGranted.values().stream().reduce(true, Boolean::logicalAnd);
                boolean allGranted = true;
                for (boolean isGranted : permissionsGranted.values())
                {
                    allGranted = allGranted && isGranted;
                }

                if (allGranted)
                {
                    // Permission is granted. Continue the action or workflow in your
                    // app.

                    // Update the ReaderList which will add any unknown reader, firing events appropriately
                    ReaderManager.sharedInstance().updateList();
                    binding.bluetoothPermissionsPrompt.setVisibility(View.GONE);
                }
                else
                {
                    // Explain to the user that the feature is unavailable because the
                    // features requires a permission that the user has denied. At the
                    // same time, respect the user's decision. Don't link to system
                    // settings in an effort to convince the user to change their
                    // decision.
                    showBluetoothPermissionDeniedConsequences();
                }
            });
}