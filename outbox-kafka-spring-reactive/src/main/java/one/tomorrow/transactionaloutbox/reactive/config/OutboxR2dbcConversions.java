/**
 * Copyright 2026 Tomorrow GmbH @ https://tomorrow.one
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
package one.tomorrow.transactionaloutbox.reactive.config;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.ConnectionFactory;
import one.tomorrow.transactionaloutbox.commons.DatabaseDialect;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.DialectResolver;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Register these conversions in the application's R2dbcCustomConversions bean when using MySQL. */
public final class OutboxR2dbcConversions {

    private OutboxR2dbcConversions() {
    }

    public static R2dbcCustomConversions create(ConnectionFactory connectionFactory) {
        var dialect = DialectResolver.getDialect(connectionFactory);
        var database = DatabaseDialect.fromProductName(connectionFactory.getMetadata().getName());
        return R2dbcCustomConversions.of(dialect, database == DatabaseDialect.MYSQL ? mysqlConverters() : List.of());
    }

    /** Can be merged with application-specific converters instead of replacing the conversion bean. */
    public static List<Converter<?, ?>> mysqlConverters() {
        return List.of(JsonWrite.INSTANCE, JsonRead.INSTANCE, InstantWrite.INSTANCE, InstantRead.INSTANCE);
    }

    @WritingConverter
    private enum JsonWrite implements Converter<Json, String> {
        INSTANCE;
        public String convert(Json source) {
            return source.asString();
        }
    }

    @ReadingConverter
    private enum JsonRead implements Converter<String, Json> {
        INSTANCE;
        public Json convert(String source) {
            return Json.of(source);
        }
    }

    @WritingConverter
    private enum InstantWrite implements Converter<Instant, LocalDateTime> {
        INSTANCE;
        public LocalDateTime convert(Instant source) {
            return LocalDateTime.ofInstant(source, ZoneOffset.UTC);
        }
    }

    @ReadingConverter
    private enum InstantRead implements Converter<LocalDateTime, Instant> {
        INSTANCE;
        public Instant convert(LocalDateTime source) {
            return source.toInstant(ZoneOffset.UTC);
        }
    }
}
