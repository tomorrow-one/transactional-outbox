# Transactional Outbox for Quarkus

This module provides a Quarkus extension for the [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html) implementation for Kafka.

## Features

- **Zero-configuration setup**: The extension automatically configures the outbox processor and required beans
- **Quarkus-native**: Built as a proper Quarkus extension with compile-time optimization
- **JPA/Hibernate ORM integration**: Works seamlessly with Quarkus Hibernate ORM
- **Configuration via application.properties**: All settings configurable through standard Quarkus configuration
- **OpenTelemetry tracing support**: Automatic tracing integration when `quarkus-opentelemetry` is present
- **Test support**: The extension supports in-memory testing using Quarkus's SmallRye in-memory connectors

## Installation

Add the following dependency to your Quarkus project:

```xml
<dependency>
    <groupId>one.tomorrow.transactional-outbox</groupId>
    <artifactId>outbox-kafka-quarkus</artifactId>
    <version>${transactional-outbox.version}</version>
</dependency>
```

Or with Gradle:

```kotlin
implementation("one.tomorrow.transactional-outbox:outbox-kafka-quarkus:${transactionalOutboxVersion}")
```

## Required Dependencies

The extension requires the following Quarkus capabilities to be present:

- `quarkus-hibernate-orm` - For JPA/database operations
- `quarkus-kafka-client` - For Kafka producer functionality

Optional dependencies:
- `quarkus-opentelemetry` - For distributed tracing support

## Database Setup

Create the required tables using Flyway, Liquibase, or your preferred migration tool. Use the DDL from:
[outbox tables SQL](../outbox-kafka-spring/src/test/resources/db/migration/V2020.06.19.22.29.00__add-outbox-tables.sql)

Example with Flyway:

```sql
-- V1__add_outbox_tables.sql
CREATE TABLE outbox_kafka
(
  id         BIGSERIAL PRIMARY KEY,
  topic      VARCHAR(249) NOT NULL,
  key        VARCHAR(249),
  value      BYTEA        NOT NULL,
  headers    JSONB,
  created    TIMESTAMP    NOT NULL DEFAULT NOW(),
  processed  TIMESTAMP
);

CREATE TABLE outbox_kafka_lock
(
  id              CHARACTER VARYING(32) PRIMARY KEY,
  owner_id        CHARACTER VARYING(128) NOT NULL,
  valid_until     TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX idx_outbox_kafka_not_processed ON outbox_kafka (id) WHERE processed IS NULL;
CREATE INDEX idx_outbox_kafka_processed ON outbox_kafka (processed);
```

## Configuration

Configure the outbox processor in your `application.properties`:

```properties
# Kafka configuration (standard Quarkus Kafka config)
kafka.bootstrap.servers=localhost:9092

# Transactional Outbox configuration
one.tomorrow.transactional-outbox.enabled=true
one.tomorrow.transactional-outbox.processing-interval=PT0.2S
one.tomorrow.transactional-outbox.lock-timeout=PT5S
one.tomorrow.transactional-outbox.lock-owner-id=my-service-instance-1
one.tomorrow.transactional-outbox.event-source=my-service

# Optional: Automatic cleanup configuration
one.tomorrow.transactional-outbox.cleanup.interval=PT1H
one.tomorrow.transactional-outbox.cleanup.retention=P30D
```

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `one.tomorrow.transactional-outbox.enabled` | `true` | Whether the outbox processor is enabled |
| `one.tomorrow.transactional-outbox.processing-interval` | `PT0.2S` | Interval between outbox processing cycles (should be significantly smaller than lock-timeout) |
| `one.tomorrow.transactional-outbox.lock-timeout` | `PT5S` | Time after which a lock is considered stale and can be acquired by another instance |
| `one.tomorrow.transactional-outbox.lock-owner-id` | *required* | Unique identifier for this instance (e.g., hostname, pod name) |
| `one.tomorrow.transactional-outbox.event-source` | *required* | Source identifier used in the `x-source` header of published messages |
| `one.tomorrow.transactional-outbox.cleanup.interval` | *(disabled)* | Interval for automatic cleanup of processed records |
| `one.tomorrow.transactional-outbox.cleanup.retention` | *(disabled)* | How long to keep processed records before deletion |

**Important Notes:**
- `lock-owner-id` must be unique per instance for proper distributed locking
- `processing-interval` should be significantly smaller than `lock-timeout`
- `lock-timeout` should be higher than typical GC pauses but smaller than acceptable message delay

## Usage

### Basic Usage

Inject the `OutboxService` and use it within JPA transactions:

```java
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import one.tomorrow.transactionaloutbox.service.OutboxService;

@ApplicationScoped
public class OrderService {

    @Inject
    OutboxService outboxService;
    
    @Inject
    EntityManager entityManager;

    @Transactional
    public void processOrder(String orderId, String customerEmail) {
        // Save your business entity
        Order order = new Order(orderId, customerEmail);
        entityManager.persist(order);

        // Prepare event for publishing
        OrderProcessedEvent event = OrderProcessedEvent.newBuilder()
            .setOrderId(orderId)
            .setCustomerEmail(customerEmail)
            .build();

        // Store message in outbox (will be published after transaction commits)
        outboxService.saveForPublishing(
            "order-events", 
            orderId, 
            event.toByteArray()
        );
    }
}
```

### With Custom Headers

```java
@Transactional
public void processOrderWithHeaders(String orderId, String customerEmail) {
    // ... business logic ...

    Map<String, String> headers = Map.of(
        "event-type", "OrderProcessed",
        "event-version", "v1",
        "correlation-id", UUID.randomUUID().toString()
    );

    outboxService.saveForPublishing(
        "order-events", 
        orderId, 
        event.toByteArray(),
        headers
    );
}
```

## Message Headers

Published messages include the following headers:

- `x-sequence`: Database sequence/ID of the outbox record (for deduplication/ordering)
- `x-source`: The configured `event-source` value, can be useful for clients and for migration scenarios
- OpenTelemetry tracing headers (when tracing is enabled)
- Any custom headers you provide

## Tracing Integration

When `quarkus-opentelemetry` is present, the extension automatically:

- Creates spans for outbox processing
- Propagates trace context to Kafka messages
- Links outbox storage and message publishing in traces

No additional configuration is required - tracing works out of the box with your existing OpenTelemetry setup.

## Testing

### In-Memory Testing (Without Kafka)

The extension supports in-memory testing using Quarkus's SmallRye in-memory connectors, allowing you to test your application without requiring a real Kafka instance. This is particularly useful for integration tests that also work with incoming / outgoing Kafka messages.

#### Setup for In-Memory Testing

1. **Configure channels to use in-memory connector:**

```properties
# Test configuration (application-test.properties or test profile)
quarkus.kafka.devservices.enabled=false

# Configure outgoing channels to use in-memory connector
mp.messaging.outgoing.my-channel.connector=smallrye-in-memory
mp.messaging.outgoing.my-channel.topic=my-topic
```

2. **Create an EmitterResolver implementation:**

```java
@ApplicationScoped
public class TestEmitterResolver implements EmitterMessagePublisher.EmitterResolver {

    @Channel("my-channel")
    MutinyEmitter<byte[]> myChannelEmitter;

    @Override
    public MutinyEmitter<byte[]> resolveBy(String topic) {
        if ("my-topic".equals(topic)) {
            return myChannelEmitter;
        }
        return null;
    }
}
```

3. **Write your test:**

```java
@QuarkusTest
public class OutboxInMemoryTest {

    @Inject
    OutboxService outboxService;

    @Inject
    @Connector("smallrye-in-memory")
    InMemoryConnector inMemoryConnector;

    private InMemorySink<byte[]> sink;

    @BeforeEach
    void setUp() {
        sink = inMemoryConnector.sink("my-channel");
    }

    @Test
    @Transactional
    void testOutboxWithInMemoryKafka() {
        // Store message in outbox
        outboxService.saveForPublishing("my-topic", "key1", "test-message".getBytes());

        // Wait for message to be processed and published
        await().atMost(5, SECONDS)
            .until(() -> sink.received(), hasSize(1));

        // Verify the published message
        Message<byte[]> message = sink.received().get(0);
        assertEquals("test-message", new String(message.getPayload()));
        
        // Get Kafka metadata for key verification
        Optional<OutgoingKafkaRecordMetadata> metadata = 
            message.getMetadata(OutgoingKafkaRecordMetadata.class);
        assertEquals("key1", metadata.orElseThrow().getKey());

        sink.clear();
    }
}
```

This testing approach is demonstrated in the `OutboxProcessorInMemoryIntegrationTest`.

## Deployment Considerations

### Multiple Instances

The extension uses database-based locking to ensure only one instance processes the outbox at a time:

- Each instance tries to acquire a lock using its `lock-owner-id`
- If an instance crashes, others can take over after `lock-timeout`
- Configure unique `lock-owner-id` per instance (e.g., using hostname or pod name)

### Native Compilation

The extension is optimized for GraalVM native compilation and works with Quarkus native builds out of the box.

## Migration from Spring

If migrating from the Spring version:

1. Replace Spring dependency with Quarkus dependency
2. Remove manual `@Configuration` classes - the extension handles bean creation
3. Move configuration from Java code to `application.properties`
4. Replace `@Autowired` with `@Inject`
5. Use `@Transactional` (Jakarta) instead of Spring's `@Transactional`

## Troubleshooting

### Common Issues

**Outbox processor not starting:**
- Check that required dependencies are present
- Verify database tables exist
- Check configuration properties

**Lock acquisition failures:**
- Ensure `lock-owner-id` is unique per instance
- Check `lock-timeout` configuration
- Verify database connectivity

**Messages not being published:**
- Check Kafka configuration
- Verify outbox processor health checks
- Check application logs for errors

### Logging

Enable debug logging for troubleshooting:

```properties
quarkus.log.category."one.tomorrow.transactionaloutbox".level=DEBUG
```
