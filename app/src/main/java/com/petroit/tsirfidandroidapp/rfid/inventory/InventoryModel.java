//----------------------------------------------------------------------------------------------
// Copyright (c) 2013 Technology Solutions UK Ltd. All rights reserved.
//----------------------------------------------------------------------------------------------

package com.petroit.tsirfidandroidapp.rfid.inventory;

import android.util.Log;

import com.petroit.tsirfidandroidapp.rfid.ModelBase;
import com.uk.tsl.rfid.asciiprotocol.commands.AbortCommand;
import com.uk.tsl.rfid.asciiprotocol.commands.AlertCommand;
import com.uk.tsl.rfid.asciiprotocol.commands.BarcodeCommand;
import com.uk.tsl.rfid.asciiprotocol.commands.FactoryDefaultsCommand;
import com.uk.tsl.rfid.asciiprotocol.commands.InventoryCommand;
import com.uk.tsl.rfid.asciiprotocol.enumerations.AlertDuration;
import com.uk.tsl.rfid.asciiprotocol.enumerations.TriState;
import com.uk.tsl.rfid.asciiprotocol.responders.IBarcodeReceivedDelegate;
import com.uk.tsl.rfid.asciiprotocol.responders.ICommandResponseLifecycleDelegate;
import com.uk.tsl.rfid.asciiprotocol.responders.ITransponderReceivedDelegate;
import com.uk.tsl.rfid.asciiprotocol.responders.TransponderData;
import com.uk.tsl.utils.HexEncoding;

import java.util.HashMap;
import java.util.Locale;

public class InventoryModel extends ModelBase
{

	// Control 
	private boolean mAnyTagSeen;
    private boolean mEnabled;
    private boolean mContinuousScanEnabled;
    private boolean mUniquesOnly;
    private int mTagsSeen = 0;
    private long alertLastIssueTime = System.nanoTime();
    private final static long sAlertRepeatDelayMs = 400 * 1000 * 1000;

	public boolean enabled() { return mEnabled; }

	public void setEnabled(boolean state)
	{
		boolean oldState = mEnabled;
		mEnabled = state;

		// Update the commander for state changes
		if(oldState != state) {
			if( mEnabled ) {
				// Listen for transponders
				getCommander().addResponder(mInventoryResponder);
				// Listen for barcodes
				getCommander().addResponder(mBarcodeResponder);
			} else {
			    if (mContinuousScanEnabled)
                {
                    scanStop();
                }
				// Stop listening for transponders
				getCommander().removeResponder(mInventoryResponder);
				// Stop listening for barcodes
				getCommander().removeResponder(mBarcodeResponder);
			}
			
		}
	}

	public boolean uniquesOnly() { return mUniquesOnly; }

	public void setUniquesOnly(boolean value)
    {
        mUniquesOnly = value;
    }

	// The command to use as a responder to capture incoming inventory responses
	private InventoryCommand mInventoryResponder;
	// The command used to issue commands
	private InventoryCommand mInventoryCommand;

	// The command to use as a responder to capture incoming barcode responses
	private BarcodeCommand mBarcodeResponder;

	// A 'Dictionary' lookup for the unique transponders seen
	private HashMap<String, TransponderData> mUniqueTransponders = new HashMap<>();

	// The inventory command configuration
	public InventoryCommand getCommand() { return mInventoryCommand; }

	// Used to indicate tags seen in continuous inventory mode
	private AlertCommand mAlertCommand;

	public InventoryModel()
	{
        mContinuousScanEnabled = false;
        mUniquesOnly = false;

        mAlertCommand = new AlertCommand();
        mAlertCommand.setDuration(AlertDuration.SHORT);

		// This is the command that will be used to perform configuration changes and inventories
		mInventoryCommand = new InventoryCommand();
        mInventoryCommand.setResetParameters(TriState.YES);
		// Configure the type of inventory
		mInventoryCommand.setIncludeTransponderRssi(TriState.YES);
		mInventoryCommand.setIncludeChecksum(TriState.YES);
        mInventoryCommand.setIncludePC(TriState.YES);
        mInventoryCommand.setIncludeDateTime(TriState.YES);

        // Handle the alerts in the App
        mInventoryCommand.setUseAlert(TriState.NO);

        // Use an InventoryCommand as a responder to capture all incoming inventory responses
		mInventoryResponder = new InventoryCommand();

		// Also capture the responses that were not from App commands 
		mInventoryResponder.setCaptureNonLibraryResponses(true);

		// Notify when each transponder is seen
		mInventoryResponder.setTransponderReceivedDelegate(new ITransponderReceivedDelegate() {

			@Override
			public void transponderReceived(TransponderData transponder, boolean moreAvailable) {

				if( transponder.getEpc() != null && !(mUniquesOnly && mUniqueTransponders.containsKey(transponder.getEpc())))
                {
                    mAnyTagSeen = true;

                    String tidMessage = transponder.getTidData() == null ? "" : HexEncoding.bytesToString(transponder.getTidData());
                    String infoMsg = String.format(Locale.US, "\nRSSI: %d  PC: %04X  CRC: %04X", transponder.getRssi(), transponder.getPc(), transponder.getCrc());
                    sendMessageNotification("EPC: " + transponder.getEpc() + infoMsg + "\nTID: " + tidMessage + "\n# " + mTagsSeen);
                    mTagsSeen++;

                    // Remember this transponder as it has not been seen before
                    if(mUniquesOnly)
                    {
                        mUniqueTransponders.put(transponder.getEpc(), transponder);
                    }
                }
				if( !moreAvailable) {
//					sendMessageNotification("");
					Log.d("TagCount",String.format("Tags seen: %s", mTagsSeen));
				}
			}
		});

		mInventoryResponder.setResponseLifecycleDelegate( new ICommandResponseLifecycleDelegate() {
			
			@Override
			public void responseEnded() {
			    // Only play sound when tags were seen
                if(mAnyTagSeen)
                {
                    // To avoid continuously running the buzzer on 11xx series Readers
                    // Ensure no new sound until after last (short) tone has finished
                    // Note: 21xx series readers do not need this
                    if( System.nanoTime() - alertLastIssueTime > sAlertRepeatDelayMs)
                    {
                        getCommander().executeCommand(mAlertCommand);
                        alertLastIssueTime = System.nanoTime();
                    }
                }
                if( mContinuousScanEnabled)
                {
                    // Issue another asynchronous scan
                    getCommander().executeCommand(mInventoryCommand);
                }
                else
                {
                    if (!mAnyTagSeen && mInventoryCommand.getTakeNoAction() != TriState.YES)
                    {
                        sendMessageNotification("No transponders seen");
                    }
                    mInventoryCommand.setTakeNoAction(TriState.NO);
                }
            }
			
			@Override
			public void responseBegan() {
				mAnyTagSeen = false;
			}
		});

		// This command is used to capture barcode responses
		mBarcodeResponder = new BarcodeCommand();
		mBarcodeResponder.setCaptureNonLibraryResponses(true);
		mBarcodeResponder.setUseEscapeCharacter(TriState.YES);
		mBarcodeResponder.setBarcodeReceivedDelegate(new IBarcodeReceivedDelegate() {
			@Override
			public void barcodeReceived(String barcode) {
				sendMessageNotification("BC: " + barcode);
			}
		});
	}

	//
	// Reset the reader configuration to default command values
	//
	public void resetDevice()
	{
		if(getCommander().isConnected()) {
            FactoryDefaultsCommand fdCommand = new FactoryDefaultsCommand();
            fdCommand.setResetParameters(TriState.YES);
            getCommander().executeCommand(fdCommand);
		}
	}
	
	//
	// Update the reader configuration from the command
	// Call this after each change to the model's command
	//
	public void updateConfiguration()
	{
		if(getCommander().isConnected()) {
            try
            {
                mInventoryCommand.setTakeNoAction(TriState.YES);
                getCommander().executeCommand(mInventoryCommand);
            }
            catch (Exception e)
            {
                sendMessageNotification(String.format(Locale.US,
                        "Exception: %s",
                        e.getMessage()
                        ));
                e.printStackTrace();
            }
            finally
            {
            }
        }
	}

    //
    // Perform an inventory scan with the current command parameters
    //
    public void scan()
    {
        testForAntenna();
        if(getCommander().isConnected()) {
            mInventoryCommand.setTakeNoAction(TriState.NO);
            getCommander().executeCommand(mInventoryCommand);
        }
    }


    //
    // Start the continuous inventory scan with the current command parameters
    //
    public void scanStart()
    {
        testForAntenna();
        if(getCommander().isConnected()) {
            mContinuousScanEnabled = true;
            mInventoryCommand.setTakeNoAction(TriState.NO);
            getCommander().executeCommand(mInventoryCommand);
        }
    }


    //
    // Stop the continuous inventory scan
    //
    public void scanStop()
    {
        mContinuousScanEnabled = false;
        mInventoryCommand.setTakeNoAction(TriState.YES);

        if(getCommander().isConnected()) {
            // Cancel any running inventory
            getCommander().executeCommand(new AbortCommand());
        }
    }


    //
	// Test for the presence of the antenna
	//
	public void testForAntenna()
	{
		if(getCommander().isConnected()) {
			InventoryCommand testCommand = InventoryCommand.synchronousCommand();
			testCommand.setTakeNoAction(TriState.YES);
			getCommander().executeCommand(testCommand);
			if( !testCommand.isSuccessful() ) {
				sendMessageNotification("ER:Error! Code: " + testCommand.getErrorCode() + " " + testCommand.getMessages().toString());
			}
		}
	}

	// Reset the unique transponder list
	public void clearUniques()
    {
        mTagsSeen = 0;
        mUniqueTransponders.clear();
    }

}


