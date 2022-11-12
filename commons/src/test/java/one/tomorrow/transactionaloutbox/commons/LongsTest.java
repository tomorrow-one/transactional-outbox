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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LongsTest {

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("longValues")
    void toLong(long value) {
        byte[] bytes = ByteBuffer.allocate(8).putLong(value).array();
        long converted = Longs.toLong(bytes);
        assertEquals(value, converted);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("longValues")
    void toByteArray(long value) {
        byte[] bytes = Longs.toByteArray(value);
        byte[] expected = ByteBuffer.allocate(8).putLong(value).array();
        assertArrayEquals(expected, bytes);
    }

    @SuppressWarnings("unused")
    static LongStream longValues() {
        Random r = new Random();
        return LongStream.concat(
                LongStream.of(-1, 0, 1, 2, Long.MAX_VALUE),
                LongStream.generate(() -> Math.abs(r.nextLong()))
        ).limit(10);
    }

}