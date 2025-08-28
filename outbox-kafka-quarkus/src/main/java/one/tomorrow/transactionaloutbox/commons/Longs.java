/**
 * Copyright 2022 Tomorrow GmbH @ https://tomorrow.one
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.tomorrow.transactionaloutbox.commons;

public class Longs {

    /**
     * Returns a big-endian representation of value in an 8-element byte array; equivalent to
     * <code>ByteBuffer.allocate(8).putLong(value).array()</code>.<br/>
     * For example, the input value <code>0x1213141516171819L</code> would yield the byte array
     * <code>{0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19}</code>.
     */
    public static byte[] toByteArray(long data) {
        return new byte[] {
                (byte) (data >>> 56),
                (byte) (data >>> 48),
                (byte) (data >>> 40),
                (byte) (data >>> 32),
                (byte) (data >>> 24),
                (byte) (data >>> 16),
                (byte) (data >>> 8),
                (byte) data
        };
    }

    /**
     * Returns the long value whose big-endian representation is stored in the given byte array (length must be 8);
     * equivalent to <code>ByteBuffer.wrap(bytes).getLong()</code>.<br/>
     * For example, the input byte array <code>{0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19}</code> would yield the
     * long value <code>0x1213141516171819L</code>.
     * @param data array of 8 bytes, the big-endian representation of the long
     * @return the long value
     * @throws IllegalArgumentException if <code>data</code> is <code>null</code> or has length != 8.
     */
    public static long toLong(byte[] data) {
        if (data == null || data.length != 8) {
            throw new IllegalArgumentException("Size of data received is not 8");
        }

        long value = 0;
        for (byte b : data) {
            value <<= 8;
            value |= b & 0xFF;
        }
        return value;
    }

}
