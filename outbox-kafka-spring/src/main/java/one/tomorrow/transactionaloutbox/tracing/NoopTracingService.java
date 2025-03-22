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
package one.tomorrow.transactionaloutbox.tracing;

import one.tomorrow.transactionaloutbox.model.OutboxRecord;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * A no-op implementation of the {@link TracingService} interface. The MicrometerTracingService
 * should be preferred if micrometer-tracing is available on the classpath, therefore it's annotated
 * with {@code @Primary}. Alternatively, we could use our own implementation of {@code @ConditionalOnMissingBean}
 * and use this class as the default/fallback implementation.
 */
@Service
public class NoopTracingService implements TracingService {

    @Override
    public Map<String, String> tracingHeadersForOutboxRecord() {
        return Collections.emptyMap();
    }

    @Override
    public TraceOutboxRecordProcessingResult traceOutboxRecordProcessing(OutboxRecord outboxRecord) {
        return new HeadersOnlyTraceOutboxRecordProcessingResult(outboxRecord.getHeaders());
    }

}
