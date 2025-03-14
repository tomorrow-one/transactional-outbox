/**
 * Copyright 2025 Tomorrow GmbH @ https://tomorrow.one
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

import java.util.HashMap;
import java.util.Map;

public class Maps {

    public static Map<String, String> merge(Map<String, String> map1, Map<String, String> map2) {
        if (isNullOrEmpty(map1))
            return map2;
        if (isNullOrEmpty(map2))
            return map1;
        Map<String, String> result = new HashMap<>(map1);
        result.putAll(map2);
        return result;
    }

    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

}
