/**
 * Copyright 2022-2023 Tomorrow GmbH @ https://tomorrow.one
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

/** SQL differences shared by the JDBC and R2DBC repositories. */
public enum DatabaseDialect {
    POSTGRESQL("jsonb", "\""),
    MYSQL("json", "`");

    private final String jsonType;
    private final String quote;

    DatabaseDialect(String jsonType, String quote) {
        this.jsonType = jsonType;
        this.quote = quote;
    }

    public static DatabaseDialect fromProductName(String name) {
        if ("PostgreSQL".equalsIgnoreCase(name))
            return POSTGRESQL;
        if ("MySQL".equalsIgnoreCase(name))
            return MYSQL;
        throw new IllegalArgumentException("Unsupported outbox database: " + name);
    }

    public String insertRecordSql() {
        return "insert into outbox_kafka (created, processed, topic, " + quote + "key" + quote
                + ", " + quote + "value" + quote + ", headers) values (?, ?, ?, ?, ?, cast(? as " + jsonType + "))";
    }

    /** Only contention errors may be treated as an unsuccessful lock attempt. */
    public static boolean isLockContention(String sqlState, int errorCode) {
        return "55P03".equals(sqlState) || "40P01".equals(sqlState) || "40001".equals(sqlState)
                || errorCode == 3572 || errorCode == 1205 || errorCode == 1213;
    }
}
