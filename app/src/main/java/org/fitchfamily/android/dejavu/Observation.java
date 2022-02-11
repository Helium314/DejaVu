package org.fitchfamily.android.dejavu;
/*
 *    DejaVu - A location provider backend for microG/UnifiedNlp
 *
 *    Copyright (C) 2017 Tod Fitch
 *
 *    This program is Free Software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as
 *    published by the Free Software Foundation, either version 3 of the
 *    License, or (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Created by tfitch on 10/5/17.
 */

import android.os.SystemClock;
import android.support.annotation.NonNull;

/**
 * A single observation made of a RF emitter.
 *
 * Used to convey all the information we have collected in the foreground about
 * a RF emitter we have seen to the background thread that actually does the
 * heavy lifting.
 *
 * It contains an identifier for the RF emitter (type and id), the received signal
 * level and optionally a note about about the emitter.
 */

public class Observation implements Comparable<Observation> {
    private final RfIdentification identification;
    private int asu;
    private String note;

    private long mLastUpdateTimeMs;
    private long mElapsedRealtimeNanos;

    Observation(String id, EmitterType t) {
        identification = new RfIdentification(id, t);
        note = "";
        asu = BackendService.MINIMUM_ASU;
        mLastUpdateTimeMs = System.currentTimeMillis();
        mElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos();
    }

    public int compareTo(@NonNull Observation o) {
        int rslt = o.asu - asu;
        if (rslt == 0)
            rslt = identification.compareTo(o.identification);
        return rslt;
    }

    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        return (toString().compareTo(o.toString()) == 0);
    }

    @Override
    public int hashCode() {
        int result = 1;

        if (identification != null)
            result = identification.hashCode();
        result = (result << 31) + asu;
        return result;
    }

    public RfIdentification getIdentification() {
        return identification;
    }

    public void setAsu(int signal) {
        if (signal > BackendService.MAXIMUM_ASU)
            asu = BackendService.MAXIMUM_ASU;
        else if (signal < BackendService.MINIMUM_ASU)
            asu = BackendService.MINIMUM_ASU;
        else
            asu = signal;
    }

    public int getAsu() {
        return asu;
    }

    public long getLastUpdateTimeMs() {
        return mLastUpdateTimeMs;
    }

    public long getElapsedRealtimeNanos() {
        return mElapsedRealtimeNanos;
    }

    public void setNote(String n) {
        note = n;
    }

    public String getNote() {
        return note;
    }

    public String toString() {
        return identification.toString() + ", asu=" + asu + ", note='" + note + "'";
    }
}
