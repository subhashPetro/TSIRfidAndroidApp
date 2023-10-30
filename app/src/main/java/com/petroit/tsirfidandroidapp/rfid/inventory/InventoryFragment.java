package com.petroit.tsirfidandroidapp.rfid.inventory;

import android.content.Context;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;


import com.petroit.tsirfidandroidapp.R;
import com.petroit.tsirfidandroidapp.databinding.FragmentInventoryBinding;
import com.petroit.tsirfidandroidapp.rfid.ModelBase;
import com.petroit.tsirfidandroidapp.rfid.WeakHandler;
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander;
import com.uk.tsl.rfid.asciiprotocol.BuildConfig;
import com.uk.tsl.rfid.asciiprotocol.DeviceProperties;
import com.uk.tsl.rfid.asciiprotocol.enumerations.QuerySession;
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState;
import com.uk.tsl.rfid.asciiprotocol.parameters.AntennaParameters;
import com.uk.tsl.utils.Observable;

public class InventoryFragment extends Fragment
{
    // Debugging
    private static final String TAG = "InventoryFragment";
    private static final boolean D = BuildConfig.DEBUG;

    private FragmentInventoryBinding binding;


    // The list of results from actions
    private ArrayAdapter<String> mResultsArrayAdapter;
    private ListView mResultsListView;
    private ArrayAdapter<String> mBarcodeResultsArrayAdapter;
    private ListView mBarcodeResultsListView;

    // The text view to display the RF Output Power used in RFID commands
    private TextView mPowerLevelTextView;
    // The seek bar used to adjust the RF Output Power for RFID commands
    private SeekBar mPowerSeekBar;
    // The current setting of the power level
    private int mPowerLevel = AntennaParameters.MaximumCarrierPower;

    // Error report
    private TextView mResultTextView;

    // Custom adapter for the session values to display the description rather than the toString() value
    public class SessionArrayAdapter extends ArrayAdapter<QuerySession> {
        private final QuerySession[] mValues;

        public SessionArrayAdapter(Context context, int textViewResourceId, QuerySession[] objects) {
            super(context, textViewResourceId, objects);
            mValues = objects;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView)super.getView(position, convertView, parent);
            view.setText(mValues[position].getDescription());
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView)super.getDropDownView(position, convertView, parent);
            view.setText(mValues[position].getDescription());
            return view;
        }
    }

    // The session
    private QuerySession[] mSessions = new QuerySession[] {
            QuerySession.SESSION_0,
            QuerySession.SESSION_1,
            QuerySession.SESSION_2,
            QuerySession.SESSION_3
    };
    // The list of sessions that can be selected
    private SessionArrayAdapter mSessionArrayAdapter;

    // All of the reader inventory tasks are handled by this class
    private InventoryModel mModel;

    // Start stop buttons
    Button mStartButton;
    Button mStopButton;



    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    )
    {

        binding = FragmentInventoryBinding.inflate(inflater, container, false);

        mResultsArrayAdapter = new ArrayAdapter<String>(this.getContext(), R.layout.result_item);
        mBarcodeResultsArrayAdapter = new ArrayAdapter<String>(this.getContext(),R.layout.result_item);

        mResultTextView = binding.resultTextView;

        // Find and set up the results ListView
        mResultsListView =binding.resultListView;
        mResultsListView.setAdapter(mResultsArrayAdapter);
        mResultsListView.setFastScrollEnabled(true);

        mBarcodeResultsListView = binding.barcodeListView;
        mBarcodeResultsListView.setAdapter(mBarcodeResultsArrayAdapter);
        mBarcodeResultsListView.setFastScrollEnabled(true);

        // Hook up the button actions
        mStartButton = binding.scanButton;
        mStartButton.setOnClickListener(mScanButtonListener);

        mStopButton = binding.scanStopButton;
        mStopButton.setOnClickListener(mScanStopButtonListener);
        mStopButton.setEnabled(false);

        Button cButton = binding.clearButton;
        cButton.setOnClickListener(mClearButtonListener);

        // The SeekBar provides an integer value for the antenna power
        mPowerLevelTextView = binding.powerTextView;
        mPowerSeekBar = binding.powerSeekBar;
        mPowerSeekBar.setOnSeekBarChangeListener(mPowerSeekBarListener);

        mSessionArrayAdapter = new SessionArrayAdapter(this.getContext(), android.R.layout.simple_spinner_item, mSessions);
        // Find and set up the sessions spinner
        Spinner spinner = binding.sessionSpinner;
        mSessionArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(mSessionArrayAdapter);
        spinner.setOnItemSelectedListener(mActionSelectedListener);
        spinner.setSelection(0);

        // Set up the "Uniques Only" Id check box listener
        CheckBox ucb = binding.uniquesCheckBox;
        ucb.setOnClickListener(mUniquesCheckBoxListener);

        // Set up Fast Id check box listener
        CheckBox cb =binding.fastIdCheckBox;
        cb.setOnClickListener(mFastIdCheckBoxListener);


        //Create a (custom) model and configure its commander and handler
        mModel = new InventoryModel();
        mModel.setCommander(getCommander());
        // The handler for model messages
        GenericHandler mGenericModelHandler = new GenericHandler(this);
        mModel.setHandler(mGenericModelHandler);


        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

    }

    @Override
    public void onDestroyView()
    {
        super.onDestroyView();
        binding = null;
    }


    //----------------------------------------------------------------------------------------------
    // Pause & Resume life cycle
    //----------------------------------------------------------------------------------------------

    @Override
    public synchronized void onPause() {
        super.onPause();

        mModel.setEnabled(false);

        // Stop observing events from the AsciiCommander
        getCommander().stateChangedEvent().removeObserver(mConnectionStateObserver);
    }

    @Override
    public synchronized void onResume() {
        super.onResume();

        mModel.setEnabled(true);

        // Observe events from the AsciiCommander
        getCommander().stateChangedEvent().addObserver(mConnectionStateObserver);

        UpdateUI();
    }


    //----------------------------------------------------------------------------------------------
    // Model notifications
    //----------------------------------------------------------------------------------------------

    private static class GenericHandler extends WeakHandler<InventoryFragment>
    {
        public GenericHandler(InventoryFragment t)
        {
            super(t);
        }

        @Override
        public void handleMessage(Message msg, InventoryFragment t)
        {
            try {
                switch (msg.what) {
                    case ModelBase.BUSY_STATE_CHANGED_NOTIFICATION:
                        //TODO: process change in model busy state
                        break;

                    case ModelBase.MESSAGE_NOTIFICATION:
                        // Examine the message for prefix
                        String message = (String)msg.obj;
                        if( message.startsWith("ER:")) {
                            t.mResultTextView.setText( message.substring(3));
                            t.mResultTextView.setBackgroundColor(0xD0FFFFFF);
                        }
                        else if( message.startsWith("BC:")) {
                            t.mBarcodeResultsListView.setVisibility(View.VISIBLE);
                            t.mBarcodeResultsArrayAdapter.add(message);
                            t.scrollBarcodeListViewToBottom();
                        } else {
                            t.mResultsArrayAdapter.add(message);
                            t.scrollResultsListViewToBottom();
                        }
                        t.UpdateUI();
                        break;

                    default:
                        break;
                }
            } catch (Exception e) {
            }

        }
    };


    //
    // Set the state for the UI controls
    //
    private void UpdateUI() {
        //boolean isConnected = getCommander().isConnected();
        //TODO: configure UI control state
    }


    private void scrollResultsListViewToBottom() {
        mResultsListView.post(new Runnable() {
            @Override
            public void run() {
                // Select the last row so it will scroll into view...
                mResultsListView.setSelection(mResultsArrayAdapter.getCount() - 1);
            }
        });
    }

    private void scrollBarcodeListViewToBottom() {
        mBarcodeResultsListView.post(new Runnable() {
            @Override
            public void run() {
                // Select the last row so it will scroll into view...
                mBarcodeResultsListView.setSelection(mBarcodeResultsArrayAdapter.getCount() - 1);
            }
        });
    }


    //----------------------------------------------------------------------------------------------
    // AsciiCommander message handling
    //----------------------------------------------------------------------------------------------

    /**
     * @return the current AsciiCommander
     */
    protected AsciiCommander getCommander()
    {
        return AsciiCommander.sharedInstance();
    }

    //
    // Handle the connection state change events from the AsciiCommander
    //
    private final Observable.Observer<String> mConnectionStateObserver = (observable, reason) ->
    {
        if (D) { Log.d(getClass().getName(), "AsciiCommander state changed - isConnected: " + getCommander().isConnected()); }

        if( getCommander().isConnected() )
        {
            // Update for any change in power limits
            setPowerBarLimits();
            // This may have changed the current power level setting if the new range is smaller than the old range
            // so update the model's inventory command for the new power value
            mModel.getCommand().setOutputPower(mPowerLevel);

            mModel.resetDevice();
            mModel.updateConfiguration();
        }

        UpdateUI();
    };


    //----------------------------------------------------------------------------------------------
    // Power seek bar
    //----------------------------------------------------------------------------------------------

    //
    // Set the seek bar to cover the range of the currently connected device
    // The power level is set to the new maximum power
    //
    private void setPowerBarLimits()
    {
        DeviceProperties deviceProperties = getCommander().getDeviceProperties();

        mPowerSeekBar.setMax(deviceProperties.getMaximumCarrierPower() - deviceProperties.getMinimumCarrierPower());
        mPowerLevel = deviceProperties.getMaximumCarrierPower();
        mPowerSeekBar.setProgress(mPowerLevel - deviceProperties.getMinimumCarrierPower());
    }


    //
    // Handle events from the power level seek bar. Update the mPowerLevel member variable for use in other actions
    //
    private SeekBar.OnSeekBarChangeListener mPowerSeekBarListener = new SeekBar.OnSeekBarChangeListener() {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Nothing to do here
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

            // Update the reader's setting only after the user has finished changing the value
            updatePowerSetting(getCommander().getDeviceProperties().getMinimumCarrierPower() + seekBar.getProgress());
            mModel.getCommand().setOutputPower(mPowerLevel);
            mModel.updateConfiguration();
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress,
                                      boolean fromUser) {
            updatePowerSetting(getCommander().getDeviceProperties().getMinimumCarrierPower() + progress);
        }
    };

    private void updatePowerSetting(int level)	{
        mPowerLevel = level;
        mPowerLevelTextView.setText( mPowerLevel + " dBm");
    }


    //----------------------------------------------------------------------------------------------
    // Button event handlers
    //----------------------------------------------------------------------------------------------

    // Scan (Start) action
    private View.OnClickListener mScanButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                mResultTextView.setText("");
                // Start the continuous inventory
                mModel.scanStart();

                mStartButton.setEnabled(false);
                mStopButton.setEnabled(true);

                mBarcodeResultsListView.setVisibility(View.GONE);
                UpdateUI();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    // Scan Stopaction
    private View.OnClickListener mScanStopButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                mResultTextView.setText("");
                // Stop the continuous inventory
                mModel.scanStop();

                mStartButton.setEnabled(true);
                mStopButton.setEnabled(false);

                UpdateUI();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    // Clear action
    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                // Clear the list
                mResultsArrayAdapter.clear();
                mResultTextView.setText("");
                mResultTextView.setBackgroundColor(0x00FFFFFF);
                mBarcodeResultsArrayAdapter.clear();
                mModel.clearUniques();

                mBarcodeResultsListView.setVisibility(View.VISIBLE);
                UpdateUI();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };

    //----------------------------------------------------------------------------------------------
    // Handler for changes in session
    //----------------------------------------------------------------------------------------------

    private AdapterView.OnItemSelectedListener mActionSelectedListener = new AdapterView.OnItemSelectedListener()
    {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            if( mModel.getCommand() != null ) {
                QuerySession targetSession = (QuerySession)parent.getItemAtPosition(pos);
                mModel.getCommand().setQuerySession(targetSession);
                mModel.updateConfiguration();
            }

        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    };


    //----------------------------------------------------------------------------------------------
    // Handler for changes in Uniques Only
    //----------------------------------------------------------------------------------------------

    private View.OnClickListener mUniquesCheckBoxListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                CheckBox uniquesCheckBox = (CheckBox)v;

                mModel.setUniquesOnly(uniquesCheckBox.isChecked());

                UpdateUI();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };


    //----------------------------------------------------------------------------------------------
    // Handler for changes in FastId
    //----------------------------------------------------------------------------------------------

    private View.OnClickListener mFastIdCheckBoxListener = new View.OnClickListener() {
        public void onClick(View v) {
            try {
                CheckBox fastIdCheckBox = (CheckBox)v;
                mModel.getCommand().setUsefastId(fastIdCheckBox.isChecked() ? TriState.YES : TriState.NO);
                mModel.updateConfiguration();

                UpdateUI();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };



}