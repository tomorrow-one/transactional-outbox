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

import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MicrometerTracingIntegrationTestConfig {

    @Bean
    public SimpleTracer simpleTracer() {
        return new SimpleTracer();
    }

    @Bean
    public Propagator propagator(SimpleTracer tracer) {
        return new SimplePropagator(tracer);
    }

    @Bean
    public TracingService tracingService(SimpleTracer tracer, Propagator propagator) {
        return new MicrometerTracingService(tracer, propagator);
    }

}
