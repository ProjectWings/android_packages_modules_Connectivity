/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.nearby.provider;

import static android.nearby.ScanRequest.SCAN_TYPE_NEARBY_PRESENCE;

import static com.android.server.nearby.NearbyService.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.location.NanoAppMessage;
import android.nearby.DataElement;
import android.nearby.NearbyDevice;
import android.nearby.NearbyDeviceParcelable;
import android.nearby.PresenceDevice;
import android.nearby.PresenceScanFilter;
import android.nearby.PublicCredential;
import android.nearby.ScanFilter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import com.google.protobuf.ByteString;

import java.util.Collections;
import java.util.concurrent.Executor;

import service.proto.Blefilter;

/** Discovery provider that uses CHRE Nearby Nanoapp to do scanning. */
public class ChreDiscoveryProvider extends AbstractDiscoveryProvider {
    // Nanoapp ID reserved for Nearby Presence.
    /** @hide */
    @VisibleForTesting public static final long NANOAPP_ID = 0x476f6f676c001031L;
    /** @hide */
    @VisibleForTesting public static final int NANOAPP_MESSAGE_TYPE_FILTER = 3;
    /** @hide */
    @VisibleForTesting public static final int NANOAPP_MESSAGE_TYPE_FILTER_RESULT = 4;
    /** @hide */
    @VisibleForTesting public static final int NANOAPP_MESSAGE_TYPE_CONFIG = 5;

    private static final int PRESENCE_UUID = 0xFCF1;
    private static final int FP_ACCOUNT_KEY_LENGTH = 16;

    private final ChreCommunication mChreCommunication;
    private final ChreCallback mChreCallback;
    private boolean mChreStarted = false;
    private Blefilter.BleFilters mFilters = null;
    private Context mContext;
    private final IntentFilter mIntentFilter;

    private final BroadcastReceiver mScreenBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d(TAG, "[ChreDiscoveryProvider] update nanoapp screen status.");
                    Boolean screenOn =
                            intent.getAction().equals(Intent.ACTION_SCREEN_ON) ? true : false;
                    sendScreenUpdate(screenOn);
                }
            };

    public ChreDiscoveryProvider(
            Context context, ChreCommunication chreCommunication, Executor executor) {
        super(context, executor);
        mContext = context;
        mChreCommunication = chreCommunication;
        mChreCallback = new ChreCallback();
        mIntentFilter = new IntentFilter();
    }

    /** Initialize the CHRE discovery provider. */
    public void init() {
        mChreCommunication.start(mChreCallback, Collections.singleton(NANOAPP_ID));
        mIntentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mIntentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenBroadcastReceiver, mIntentFilter);
    }

    @Override
    protected void onStart() {
        Log.d(TAG, "Start CHRE scan");
        updateFilters();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "Stop CHRE scan");
        mScanFilters.clear();
        updateFilters();
    }

    @Override
    protected void invalidateScanMode() {
        onStop();
        onStart();
    }

    public boolean available() {
        return mChreCommunication.available();
    }

    private synchronized void updateFilters() {
        if (mScanFilters == null) {
            Log.e(TAG, "ScanFilters not set.");
            return;
        }
        Blefilter.BleFilters.Builder filtersBuilder = Blefilter.BleFilters.newBuilder();
        for (ScanFilter scanFilter : mScanFilters) {
            PresenceScanFilter presenceScanFilter = (PresenceScanFilter) scanFilter;
            Blefilter.BleFilter.Builder filterBuilder = Blefilter.BleFilter.newBuilder();
            for (DataElement dataElement : presenceScanFilter.getExtendedProperties()) {
                if (dataElement.getKey() == DataElement.DataType.ACCOUNT_KEY) {
                    Blefilter.DataElement filterDe =
                            Blefilter.DataElement.newBuilder()
                                    .setKey(
                                            Blefilter.DataElement.ElementType
                                                    .DE_FAST_PAIR_ACCOUNT_KEY)
                                    .setValue(ByteString.copyFrom(dataElement.getValue()))
                                    .setValueLength(FP_ACCOUNT_KEY_LENGTH)
                                    .build();
                    filterBuilder.addDataElement(filterDe);
                }
            }
            Log.i(TAG, "add filter");
            filtersBuilder.addFilter(filterBuilder.build());
        }
        mFilters = filtersBuilder.build();
        if (mChreStarted) {
            sendFilters(mFilters);
            mFilters = null;
        }
    }

    private void sendFilters(Blefilter.BleFilters filters) {
        NanoAppMessage message =
                NanoAppMessage.createMessageToNanoApp(
                        NANOAPP_ID, NANOAPP_MESSAGE_TYPE_FILTER, filters.toByteArray());
        if (!mChreCommunication.sendMessageToNanoApp(message)) {
            Log.e(TAG, "Failed to send filters to CHRE.");
        }
    }

    private void sendScreenUpdate(Boolean screenOn) {
        Blefilter.BleConfig config = Blefilter.BleConfig.newBuilder().setScreenOn(screenOn).build();
        NanoAppMessage message =
                NanoAppMessage.createMessageToNanoApp(
                        NANOAPP_ID, NANOAPP_MESSAGE_TYPE_CONFIG, config.toByteArray());
        if (!mChreCommunication.sendMessageToNanoApp(message)) {
            Log.e(TAG, "Failed to send config to CHRE.");
        }
    }

    private class ChreCallback implements ChreCommunication.ContextHubCommsCallback {

        @Override
        public void started(boolean success) {
            if (success) {
                synchronized (ChreDiscoveryProvider.this) {
                    Log.i(TAG, "CHRE communication started");
                    mChreStarted = true;
                    if (mFilters != null) {
                        sendFilters(mFilters);
                        mFilters = null;
                    }
                }
            }
        }

        @Override
        public void onHubReset() {
            // TODO(b/221082271): hooked with upper level codes.
            Log.i(TAG, "CHRE reset.");
        }

        @Override
        public void onNanoAppRestart(long nanoAppId) {
            // TODO(b/221082271): hooked with upper level codes.
            Log.i(TAG, String.format("CHRE NanoApp %d restart.", nanoAppId));
        }

        @Override
        public void onMessageFromNanoApp(NanoAppMessage message) {
            if (message.getNanoAppId() != NANOAPP_ID) {
                Log.e(TAG, "Received message from unknown nano app.");
                return;
            }
            if (mListener == null) {
                Log.e(TAG, "the listener is not set in ChreDiscoveryProvider.");
                return;
            }
            if (message.getMessageType() == NANOAPP_MESSAGE_TYPE_FILTER_RESULT) {
                try {
                    Blefilter.BleFilterResults results =
                            Blefilter.BleFilterResults.parseFrom(message.getMessageBody());
                    for (Blefilter.BleFilterResult filterResult : results.getResultList()) {
                        // TODO(b/234653356): There are some duplicate fields set both in
                        //  PresenceDevice and NearbyDeviceParcelable, cleanup is needed.
                        byte[] salt = {1};
                        byte[] secretId = {1};
                        byte[] authenticityKey = {1};
                        byte[] publicKey = {1};
                        byte[] encryptedMetaData = {1};
                        byte[] encryptedMetaDataTag = {1};
                        if (filterResult.hasPublicCredential()) {
                            Blefilter.PublicCredential credential =
                                    filterResult.getPublicCredential();
                            secretId = credential.getSecretId().toByteArray();
                            authenticityKey = credential.getAuthenticityKey().toByteArray();
                            publicKey = credential.getPublicKey().toByteArray();
                            encryptedMetaData = credential.getEncryptedMetadata().toByteArray();
                            encryptedMetaDataTag =
                                    credential.getEncryptedMetadataTag().toByteArray();
                        }
                        PresenceDevice.Builder presenceDeviceBuilder =
                                new PresenceDevice.Builder(
                                                String.valueOf(filterResult.hashCode()),
                                                salt,
                                                secretId,
                                                encryptedMetaData)
                                        .setRssi(filterResult.getRssi())
                                        .addMedium(NearbyDevice.Medium.BLE);
                        // Fast Pair account keys added to Data Elements.
                        for (Blefilter.DataElement element : filterResult.getDataElementList()) {
                            if (element.getKey()
                                    == Blefilter.DataElement.ElementType.DE_FAST_PAIR_ACCOUNT_KEY) {
                                presenceDeviceBuilder.addExtendedProperty(
                                        new DataElement(
                                                DataElement.DataType.ACCOUNT_KEY,
                                                element.getValue().toByteArray()));
                            }
                        }
                        // BlE address appended to Data Element.
                        if (filterResult.hasBluetoothAddress()) {
                            presenceDeviceBuilder.addExtendedProperty(
                                    new DataElement(
                                            DataElement.DataType.BLE_ADDRESS,
                                            filterResult.getBluetoothAddress().toByteArray()));
                        }
                        // BLE Service data appended to Data Elements.
                        if (filterResult.hasBleServiceData()) {
                            presenceDeviceBuilder.addExtendedProperty(
                                    new DataElement(
                                            DataElement.DataType.BLE_SERVICE_DATA,
                                            filterResult.getBleServiceData().toByteArray()));
                        }

                        PublicCredential publicCredential =
                                new PublicCredential.Builder(
                                                secretId,
                                                authenticityKey,
                                                publicKey,
                                                encryptedMetaData,
                                                encryptedMetaDataTag)
                                        .build();

                        NearbyDeviceParcelable device =
                                new NearbyDeviceParcelable.Builder()
                                        .setScanType(SCAN_TYPE_NEARBY_PRESENCE)
                                        .setMedium(NearbyDevice.Medium.BLE)
                                        .setTxPower(filterResult.getTxPower())
                                        .setRssi(filterResult.getRssi())
                                        .setAction(0)
                                        .setPublicCredential(publicCredential)
                                        .setPresenceDevice(presenceDeviceBuilder.build())
                                        .build();
                        mExecutor.execute(() -> mListener.onNearbyDeviceDiscovered(device));
                    }
                } catch (Exception e) {
                    Log.e(TAG, String.format("Failed to decode the filter result %s", e));
                }
            }
        }
    }
}
