[![ci](https://github.com/tomorrow-one/transactional-outbox/actions/workflows/gradle-build.yml/badge.svg)](https://github.com/tomorrow-one/transactional-outbox/actions/workflows/gradle-build.yml)
[![maven: outbox-kafka-spring](https://img.shields.io/maven-central/v/one.tomorrow.transactional-outbox/outbox-kafka-spring.svg?label=maven:%20outbox-kafka-spring)](https://central.sonatype.com/search?q=one.tomorrow.transactional-outbox:outbox-kafka-spring)
[![maven: outbox-kafka-spring-reactive](https://img.shields.io/maven-central/v/one.tomorrow.transactional-outbox/outbox-kafka-spring-reactive.svg?label=maven:%20outbox-kafka-spring-reactive)](https://central.sonatype.com/search?q=one.tomorrow.transactional-outbox:outbox-kafka-spring-reactive)

# Transactional Outbox

This library is an implementation of the [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
for Kafka.

In short: when a service handles a command and has to change state in the database _and_ publish a message/event to Kafka,
either both or none shall be done. I.e. if the database transaction fails, the message to Kafka must not be published.
As solution to this problem, the message(s) that shall be published to Kafka is stored in the database in the same transaction,
and eventually published to Kafka (after the transaction was successfully committed).

### Functionality

The application stores the serialized message that shall be published (to a certain topic) in the same transaction as the business object is changed.

The library continuously processes the outbox and publishes the serialized message together with some headers
to the specified topic. Messages are published with the following guarantees:
* *strict ordering*: i.e. messages are published in the order
they're stored in the outbox. *Note*: for this the kafka producer should have set `enable.idempotence = true` to enforce valid related settings according to [enable.idempotence docs](https://kafka.apache.org/documentation/#producerconfigs_enable.idempotence) (which is set automatically in the default setup as shown below). If you need different/custom settings you probably know what you're doing, and consciously choose appropriate settings of e.g. `max.in.flight.requests.per.connection`, `retries` and `acks`.
* *at-least-once delivery*: every message from the outbox is published at least once, i.e. in case of errors (e.g. database unavailability or network errors or process crashes/restarts) there may be duplicates. Consumers are responsible for deduplication.

Messages are published with the headers `x-sequence`, `x-source` and maybe `x-value-type`:
* `x-sequence` is set to the database sequence/id of the message in the outbox table. It can be used by consumers to deduplicate or check ordering.
* `x-source` shall help consumers to distinguish between different producers of a message, which is useful in
migration scenarios. You'll specify which value for `x-source` shall be used.
* `x-value-type` is set to the fully-qualified name of the protobuf message (within the proto language's namespace) if the `ProtobufOutboxService` is used.
  Consumers can use this header to select the appropriate deserializer / protobuf message type to parse the received data/payload.

If tracing is used, tracing information is propagated via message headers (see also [Tracing](#tracing) for more information).  

To allow operation in a service running with multiple instances, a lock is managed using the database, so that only one of the instances
processes the outbox and publishes messages. All instances monitor that lock, and one of the instances will take over the lock when
the lock-holding instance crashed (or is stuck somehow and does no longer refresh the lock).

### Alternatives

This library was created because alternative solutions like Debezium or Kafka Connect would require additional operational
efforts (such a solution would have to be operated in a highly available fashion and would have to be monitored).
Additionally, these solutions limit flexibility, e.g. for the usage of custom headers for a kafka message (depending on
the message / payload), a solution would have to be found or developed. At the time of evaluation there was also no existing
experience in the team with Debezium or Kafka Connect.

### Current Limitations
* This library assumes and uses Spring (for transaction handling)
* It comes with a module for usage in classic spring and spring boot projects using sync/blocking operations (this
  module uses spring-jdbc), and another module for reactive operations (uses [spring R2DBC](https://spring.io/projects/spring-data-r2dbc) for database access)
* It's tested with postgresql only (verified support for other databases could be contributed)

## Installation & Configuration

### Add Library

Depending on your application add one of the following libraries as dependency to your project:

* classic (sync/blocking): `one.tomorrow.transactional-outbox:outbox-kafka-spring:$version`
* reactive: `one.tomorrow.transactional-outbox:outbox-kafka-spring-reactive:$version`

#### Compatibility Matrix

| Spring Version | Spring Boot Version | outbox-kafka-spring Version | outbox-kafka-spring-reactive Version |
|----------------|---------------------|-----------------------------|--------------------------------------|
| 6.2.x          | 3.4.x               | 3.5.x                       | 3.4.x                                |
| 6.1.x          | 3.2.x - 3.3.x       | 3.3.x - 3.4.x               | 3.2.x - 3.3.x                        |
| 6.0.x          | 3.1.x               | 2.0.x - 3.2.x               | 2.0.x - 3.1.x                        |
| 5.x.x          | 2.x.x               | 1.2.x                       | 1.1.x                                |

### Prepare Database

Create the tables using your preferred database migration tool: use the DDLs
from [this sql file](outbox-kafka-spring/src/test/resources/db/migration/V2020.06.19.22.29.00__add-outbox-tables.sql) (
or for reactive
projects [this one](outbox-kafka-spring-reactive/src/test/resources/db/migration/V2020.06.19.22.29.00__add-outbox-tables.sql))
.

You should review if the column restrictions for `topic`, `key` and `owner_id` match your use case / context.
The `owner_id` column (of the `outbox_kafka_lock` table) stores the unique id that you provide for identifying the
instance obtaining the lock for processing the outbox (you could e.g. use the hostname for this, assuming a unique
hostname
per instance).

### Setup the `OutboxProcessor`

The `OutboxProcessor` is the component which processes the outbox and publishes messages/events to Kafka, once it could
obtain the lock. If it could not obtain the lock on startup, it will continuously monitor the lock and try to obtain
it (in case the lock-holding instance crashed or could no longer refresh the lock).

*Note*: so that messages are published in-order as expected, the producer must be configured with
`enable.idempotence = true` (to enforce valid related settings according to [enable.idempotence docs](https://kafka.apache.org/documentation/#producerconfigs_enable.idempotence)) or - if you have reasons to not use `enable.idempotence = true` - appropriate settings of at least `max.in.flight.requests.per.connection`, `retries` and `acks`.
If you're using the `DefaultKafkaProducerFactory` as shown below, it will set `enable.idempotence = true` if the provided `producerProps` do not have set `enable.idempotence` already.

#### Setup the `OutboxProcessor` from `outbox-kafka-spring` (classic projects)

```java
@Configuration
@ComponentScan(basePackages = "one.tomorrow.transactionaloutbox")
public class TransactionalOutboxConfig {

    private Duration processingInterval = Duration.ofMillis(200);
    private Duration outboxLockTimeout = Duration.ofSeconds(5);
    private String lockOwnerId = lockOwnerId();
    private String eventSource = "my-service";
    private CleanupSettings cleanupSettings = CleanupSettings.builder()
                .interval(Duration.ofDays(1))
                .retention(Duration.ofDays(30))
                .build();

    @Bean
    public OutboxProcessor outboxProcessor(
                OutboxRepository repository,
                Map<String, Object> producerProps,
                TracingService tracingService,
                AutowireCapableBeanFactory beanFactory
    ) {
        return new OutboxProcessor(
                repository,
                new DefaultKafkaProducerFactory(producerProps),
                processingInterval,
                outboxLockTimeout,
                lockOwnerId,
                eventSource,
                cleanupSettings,
                tracingService,
                beanFactory
        );
    }

    private static String lockOwnerId() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { throw new RuntimeException(e); }
    }

}
```

* `OutboxRepository`: can be instantiated by Spring, only asking for a `JdbcTemplate`
* `Duration processingInterval`: the interval to wait after the outbox was processed completely before it's processed
  again. This value should be significantly smaller than `outboxLockTimeout` (described next). If it's higher, this is still not an issue,
  then another instance might take over the lock in the meantime (after `outboxLockTimeout` has been exceeded) and process
  the outbox.
    * Proposed value: 200ms
* `Duration outboxLockTimeout`: the time after that a lock should be considered to be timed out
    * a lock can be taken over by another instance only after that time had passed without a lock refresh by the lock owner
    * the chosen value should be higher than the 99%ile of gc pauses; but even if you'd use a smaller value (and lock would
      often get lost due to gc pauses) the library would still work correctly
    * the chosen value should be smaller than the max message publishing delay that you'd like to see (e.g. in deployment scenarios)
    * Proposed value: 5s
* `String lockOwnerId`: used to identify the instance trying to obtain the lock, **must be unique** per instance (!) (you could e.g. use the hostname)
* `String eventSource`: used as value for the `x-source` header set for a message published to Kafka
* `CleanupSettings cleanupSettings`: specifies the interval for cleaning up the outbox and the retention time for processed records, i.e. how long to keep processed records before deleting them. Set `cleanupSettings` to `null` if you prefer manual or no cleanup at all. See also [How to house keep your outbox table](#how-to-house-keep-your-outbox-table) below.
* `Map<String, Object> producerProps`: the properties used to create the `KafkaProducer` (contains e.g. `bootstrap.servers` etc)
* `TracingService tracingService`: if [micrometer-tracing](https://docs.micrometer.io/tracing/reference/index.html) is on the classpath, our `MicrometerTracingService` bean will be used, otherwise it will fall back to `NoopTracingService`. You can also provide your own implementation of our `TracingService` interface. For more details on tracing [see below](#tracing).
* `AutowireCapableBeanFactory beanFactory`: used to create the lock service (`OutboxLockService`)


#### Setup the `OutboxProcessor` from `outbox-kafka-spring-reactive`

Only slightly different looks the setup of the `OutboxProcessor` for reactive applications:

```java
@Configuration
@ComponentScan(basePackages = "one.tomorrow.transactionaloutbox.reactive")
public class TransactionalOutboxConfig {

    private Duration processingInterval = Duration.ofMillis(200);
    private Duration outboxLockTimeout = Duration.ofSeconds(5);
    private String lockOwnerId = lockOwnerId();
    private String eventSource = "my-service";
    private CleanupSettings cleanupSettings = CleanupSettings.builder()
            .interval(Duration.ofDays(1))
            .retention(Duration.ofDays(30))
            .build();

    @Bean
    public OutboxProcessor outboxProcessor(
            OutboxRepository repository,
            OutboxLockService lockService,
            Map<String, Object> producerProps,
            TracingService tracingService
    ) {
        return new OutboxProcessor(
                repository,
                lockService,
                new DefaultKafkaProducerFactory(producerProps),
                processingInterval,
                outboxLockTimeout,
                lockOwnerId,
                eventSource,
                cleanupSettings,
                tracingService
        );
    }

    private static String lockOwnerId() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { throw new RuntimeException(e); }
    }

}
```

## Usage

In a service that changes the database (inside a transaction), create and serialize the message/event that should
be published to Kafka transactionally (i.e. only if the current transaction could be committed).

In a **classic application** this could look like this:

```java
@Autowired
private OutboxService outboxService;

@Transactional
public void doSomething(String id, String name) {

    // Here s.th. else would be done within the transaction, e.g. some entity created.

    SomeEvent event = SomeEvent.newBuilder()
            .setId(id)
            .setName(name)
            .build();
    Map<String, String> headers = Map.of(KafkaHeaders.HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
    outboxService.saveForPublishing("some-topic", id, event.toByteArray(), headers);
}
```

If you're in fact using protobuf for message serialization, you can use the `ProtobufOutboxService` as a shortcut.

In a **reactive application** it would look like this (you could also use `@Transactional` if you'd prefer this rather than using the `TransactionalOperator`):

```java
@Autowired
private OutboxService outboxService;
@Autowired
private TransactionalOperator rxtx;

public Mono<OutboxRecord> doSomething(String name) {

    // Here s.th. else would be done within the transaction, e.g. some entity created.
    return createSomeThing(name)
        .flatMap(someThing -> {
            SomeEvent event = SomeEvent.newBuilder()
                .setId(someThing.getId())
                .setName(someThing.getName())
                .build();
            Map<String, String> headers = Map.of(KafkaHeaders.HEADERS_VALUE_TYPE_NAME, event.getDescriptorForType().getFullName());
            return outboxService.saveForPublishing("some-topic", someThing.getId(), event.toByteArray(), headers);
        })
        .as(rxtx::transactional);

}
```

### Tracing

If you have tracing in place you're probably interested in getting the trace context propagated with Kafka messages as well.
We provide tracing context propagation out of the box for [micrometer-tracing](https://docs.micrometer.io/tracing/reference/index.html). If you're using something else you can provide your own implementation of the `TracingService` interface (for inspiration see `MicrometerTracingService`). If micrometer-tracing is not on the classpath, by default the `NoopTracingService` bean will be used as implementation.

The tracing context is propagated if there's an active tracing context when `OutboxService.saveForPublishing` is invoked.
If this is the case, the following will be provided:
* A span for the message in the transactional-outbox is created (with start timestamp set to the time 
  when the outbox record was created, and end timestamp set to the time when it's processed and sent to Kafka)
* Another span is created for sending the message to Kafka (start timestamp is the time when the `ProducerRecord` 
  is created, end timestamp is set when message sending got confirmed)
* Tracing headers are added to the Kafka message (using [Propagator.inject](https://javadoc.io/static/io.micrometer/micrometer-tracing/1.5.0-M3/io/micrometer/tracing/propagation/Propagator.html#inject(io.micrometer.tracing.TraceContext,java.lang.Object,io.micrometer.tracing.propagation.Propagator.Setter))), which can be extracted via
[Propagator.extract](https://javadoc.io/static/io.micrometer/micrometer-tracing/1.5.0-M3/io/micrometer/tracing/propagation/Propagator.html#extract(java.lang.Object,io.micrometer.tracing.propagation.Propagator.Getter)) on consumer side to continue or link the trace.

With the implementation for micrometer-tracing we rely on the related config btw, e.g. regarding sampling probability or tracing protocol W3C/B3.

You might also want to check the [Spring Boot docs for tracing](https://javadoc.io/static/io.micrometer/micrometer-tracing/1.5.0-M3/io/micrometer/tracing/propagation/Propagator.html#extract(java.lang.Object,io.micrometer.tracing.propagation.Propagator.Getter)).

### How-To re-publish a message

In some cases, you need to re-publish a message. One example is when the consumer had an error on their side and fixed it now, then they still need the missed messages and had also had no DLT in place. For this, you can follow these steps:
1. Go into your database client and connect to the database of the sender
2. Find the right message entry in the table `outbox_kafka`
3. Update the `processed` to `NULL`. For example, with the following query:
    ```
    UPDATE outbox_kafka SET processed = NULL WHERE id = {id_from_message_to_be_republished}
    ```
4. After a very short moment, the message should be processed and published again.

If you have any questions, feel free to ask in the GitHub Discussions.


### How to house keep your outbox table

As the outbox table might contain data that you want to delete in regular intervals we provide a convenience cleanup method in the outbox repositories to delete messages processed before a certain point in time. Make sure you have created an index on your outbox tables before using these cleanup methods:
```sql
CREATE INDEX idx_outbox_kafka_processed ON outbox_kafka (processed);
```

You could run regular scheduled jobs in your application that can trigger the cleanup methods in an interval suitable for your use case:

```java
public class Cleaner {
    @Autowired
    private OutboxRepository outboxRepository;

    private void cleanupOlderThanThirtyDays() {
        outboxRepository.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now().minus(Duration.ofDays(30)));
    }
}
```

or for reactive context:

```java
public class Cleaner {
  @Autowired
  private OutboxRepository outboxRepository;

  private void cleanupOlderThanThirtyDaysReactive() {
    outboxRepository.deleteOutboxRecordByProcessedNotNullAndProcessedIsBefore(Instant.now().minus(Duration.ofDays(30))).block();
  }
}
```

**Note:** if you don't use a clustered job scheduling so that all instances would run this (maybe concurrently) you can also check `OutboxProcessor.isActive()` as a guard for performing the cleanup.

## How-To Release

To release a new version follow this step
1. In your PR with the functional change, bump the version of `commons`, `outbox-kafka-spring` or `outbox-kafka-spring-reactive` in the root `build.gradle.kts` to a non-`SNAPSHOT` version.
   * Try to follow semantic versioning, i.e. bump the major version for binary incompatible changes, the minor version for compatible changes with improvements/new features, and the patch version for bugfixes or non-functional changes like refactorings.
2. Merge your PR - the related pipeline will publish the new version(s) to Sonatype's staging repo (SNAPSHOTs are published to Maven Central Snapshots repository (and are kept for 90 days)).
3. To publish a release, follow https://central.sonatype.com/publishing/deployments
4. Push the released version(s) to the next SNAPSHOT version (choose the next higher patch version for this) - totally fine to push this to master directly
