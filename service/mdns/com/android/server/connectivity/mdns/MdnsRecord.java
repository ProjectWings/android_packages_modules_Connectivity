/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import android.os.SystemClock;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for mDNS records. Stores the header fields and provides methods for reading
 * the record from and writing it to a packet.
 */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
public abstract class MdnsRecord {
    public static final int TYPE_A = 0x0001;
    public static final int TYPE_AAAA = 0x001C;
    public static final int TYPE_PTR = 0x000C;
    public static final int TYPE_SRV = 0x0021;
    public static final int TYPE_TXT = 0x0010;

    /** Status indicating that the record is current. */
    public static final int STATUS_OK = 0;
    /** Status indicating that the record has expired (TTL reached 0). */
    public static final int STATUS_EXPIRED = 1;
    /** Status indicating that the record should be refreshed (Less than half of TTL remains.) */
    public static final int STATUS_NEEDS_REFRESH = 2;

    protected final String[] name;
    private final int type;
    private final int cls;
    private final long receiptTimeMillis;
    private final long ttlMillis;
    private Object key;

    /**
     * Constructs a new record with the given name and type.
     *
     * @param reader The reader to read the record from.
     * @throws IOException If an error occurs while reading the packet.
     */
    protected MdnsRecord(String[] name, int type, MdnsPacketReader reader) throws IOException {
        this.name = name;
        this.type = type;
        cls = reader.readUInt16();
        ttlMillis = TimeUnit.SECONDS.toMillis(reader.readUInt32());
        int dataLength = reader.readUInt16();

        receiptTimeMillis = SystemClock.elapsedRealtime();

        reader.setLimit(dataLength);
        readData(reader);
        reader.clearLimit();
    }

    /**
     * Converts an array of labels into their dot-separated string representation. This method
     * should
     * be used for logging purposes only.
     */
    public static String labelsToString(String[] labels) {
        if (labels == null) {
            return null;
        }
        return TextUtils.join(".", labels);
    }

    /** Tests if |list1| is a suffix of |list2|. */
    public static boolean labelsAreSuffix(String[] list1, String[] list2) {
        int offset = list2.length - list1.length;

        if (offset < 1) {
            return false;
        }

        for (int i = 0; i < list1.length; ++i) {
            if (!list1[i].equals(list2[i + offset])) {
                return false;
            }
        }

        return true;
    }

    /** Returns the record's receipt (creation) time. */
    public final long getReceiptTime() {
        return receiptTimeMillis;
    }

    /** Returns the record's name. */
    public String[] getName() {
        return name;
    }

    /** Returns the record's original TTL, in milliseconds. */
    public final long getTtl() {
        return ttlMillis;
    }

    /** Returns the record's type. */
    public final int getType() {
        return type;
    }

    /**
     * Returns the record's remaining TTL.
     *
     * @param now The current system time.
     * @return The remaning TTL, in milliseconds.
     */
    public long getRemainingTTL(final long now) {
        long age = now - receiptTimeMillis;
        if (age > ttlMillis) {
            return 0;
        }

        return ttlMillis - age;
    }

    /**
     * Reads the record's payload from a packet.
     *
     * @param reader The reader to use.
     * @throws IOException If an I/O error occurs.
     */
    protected abstract void readData(MdnsPacketReader reader) throws IOException;

    /**
     * Writes the record to a packet.
     *
     * @param writer The writer to use.
     * @param now    The current system time. This is used when writing the updated TTL.
     */
    @VisibleForTesting
    public final void write(MdnsPacketWriter writer, long now) throws IOException {
        writer.writeLabels(name);
        writer.writeUInt16(type);
        writer.writeUInt16(cls);

        writer.writeUInt32(TimeUnit.MILLISECONDS.toSeconds(getRemainingTTL(now)));

        int dataLengthPos = writer.getWritePosition();
        writer.writeUInt16(0); // data length
        int dataPos = writer.getWritePosition();

        writeData(writer);

        // Calculate amount of data written, and overwrite the data field earlier in the packet.
        int endPos = writer.getWritePosition();
        int dataLength = endPos - dataPos;
        writer.rewind(dataLengthPos);
        writer.writeUInt16(dataLength);
        writer.unrewind();
    }

    /**
     * Writes the record's payload to a packet.
     *
     * @param writer The writer to use.
     * @throws IOException If an I/O error occurs.
     */
    protected abstract void writeData(MdnsPacketWriter writer) throws IOException;

    /** Gets the status of the record. */
    public int getStatus(final long now) {
        final long age = now - receiptTimeMillis;
        if (age > ttlMillis) {
            return STATUS_EXPIRED;
        }
        if (age > (ttlMillis / 2)) {
            return STATUS_NEEDS_REFRESH;
        }
        return STATUS_OK;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MdnsRecord)) {
            return false;
        }

        MdnsRecord otherRecord = (MdnsRecord) other;

        return Arrays.equals(name, otherRecord.name) && (type == otherRecord.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(name), type);
    }

    /**
     * Returns an opaque object that uniquely identifies this record through a combination of its
     * type
     * and name. Suitable for use as a key in caches.
     */
    public final Object getKey() {
        if (key == null) {
            key = new Key(type, name);
        }
        return key;
    }

    private static final class Key {
        private final int recordType;
        private final String[] recordName;

        public Key(int recordType, String[] recordName) {
            this.recordType = recordType;
            this.recordName = recordName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }

            Key otherKey = (Key) other;

            return (recordType == otherKey.recordType) && Arrays.equals(recordName,
                    otherKey.recordName);
        }

        @Override
        public int hashCode() {
            return (recordType * 31) + Arrays.hashCode(recordName);
        }
    }
}