/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.profiles;

import com.graphhopper.storage.IntsRef;

/**
 * This class holds a decimal value and stores it as an integer value via a fixed factor and a maximum number
 * of bits.
 */
public class DecimalEncodedValue extends IntEncodedValue {
    private final double factor;

    public DecimalEncodedValue(String name, int bits, double defaultValue, double factor, boolean store2DirectedValues) {
        super(name, bits, 0, store2DirectedValues);
        this.factor = factor;
        this.defaultValue = toInt(defaultValue);
    }

    /**
     * TODO This method is important to 'spread' an encoded value when a way is split due to e.g. a virtual node
     */
    // public final boolean isLengthDependent() { return false; }
    private int toInt(double val) {
        return (int) Math.round(val / factor);
    }

    /**
     * This method stores the specified double value (rounding with a previously defined factor) into the IntsRef.
     */
    public final void setDecimal(boolean reverse, IntsRef ints, double value) {
        if (value > maxValue * factor)
            throw new IllegalArgumentException(getName() + " value " + value + " too large for encoding. maxValue:" + maxValue * factor);
        if (value < 0)
            throw new IllegalArgumentException("Negative value for " + getName() + " not allowed! " + value);
        if (Double.isNaN(value))
            throw new IllegalArgumentException("NaN value for " + getName() + " not allowed!");

        super.setInt(reverse, ints, toInt(value));
    }

    public final double getDecimal(boolean reverse, IntsRef ref) {
        int value = getInt(reverse, ref);
        return value * factor;
    }
}
