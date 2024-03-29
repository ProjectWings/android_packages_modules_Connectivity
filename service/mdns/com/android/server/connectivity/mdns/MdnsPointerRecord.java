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

import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.Arrays;

/** An mDNS "PTR" record, which holds a name (the "pointer"). */
// TODO(b/177655645): Resolve nullness suppression.
@SuppressWarnings("nullness")
@VisibleForTesting
public class MdnsPointerRecord extends MdnsRecord {
    private String[] pointer;

    public MdnsPointerRecord(String[] name, MdnsPacketReader reader) throws IOException {
        super(name, TYPE_PTR, reader);
    }

    /** Returns the pointer as an array of labels. */
    public String[] getPointer() {
        return pointer;
    }

    @Override
    protected void readData(MdnsPacketReader reader) throws IOException {
        pointer = reader.readLabels();
    }

    @Override
    protected void writeData(MdnsPacketWriter writer) throws IOException {
        writer.writeLabels(pointer);
    }

    public boolean hasSubtype() {
        return (name != null) && (name.length > 2) && name[1].equals(MdnsConstants.SUBTYPE_LABEL);
    }

    public String getSubtype() {
        return hasSubtype() ? name[0] : null;
    }

    @Override
    public String toString() {
        return "PTR: " + labelsToString(name) + " -> " + labelsToString(pointer);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31) + Arrays.hashCode(pointer);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MdnsPointerRecord)) {
            return false;
        }

        return super.equals(other) && Arrays.equals(pointer, ((MdnsPointerRecord) other).pointer);
    }
}