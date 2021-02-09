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
to the specified topic. Messages are published with strict ordering guarantees, i.e. messages are published in the order
they're stored in the outbox. In consequence, if a message could not be published, it will not try to publish the next message.

Messages are published with the headers `x-value-type`, `x-sequence` and `x-source`:
* `x-value-type` is set to the fully-qualified name of the protobuf message (within the proto language's namespace).
   Consumers can use this to select the appropriate deserializer / protobuf message type to parse the received data/payload.
* `x-sequence` is set to the database sequence/id of the message in the outbox table. It can be used by consumers to deduplicate or check ordering.
* `x-source` shall help consumers to distinguish between different producers of a message, which is useful in
  migration scenarios. You'll specify which value for `x-source` shall be used.

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
* This library assumes and uses Hibernate and Spring (for transaction handling) - extracting a core and making the usage of Spring stuff an optional add-on would be a possible contribution
* Currently it supports protobuf 3 for messages to publish (could be extended to other serialization libs)
* It's tested with postgresql only (verified support for other databases could be contributed)

## Installation & Configuration

### Add Library

Add the library dependency to your project: `one.tomorrow.transactional-outbox:outbox-kafka-spring:$version`

### Prepare Database

Create the tables using your preferred database migration tool: use the DDLs from [this sql file](outbox-kafka-spring/src/test/resources/db/migration/V2020.06.19.22.29.00__add-outbox-tables.sql).

You should review if the column restrictions for `topic`, `key` and `owner_id` match your use case / context.
The `owner_id` column (of the `outbox_kafka_lock` table) stores the unique id that you provide for identifying the
instance obtaining the lock for processing the outbox (you could e.g. use the hostname for this, assuming a unique hostname
per instance). 

### Setup the `OutboxProcessor`

The `OutboxProcessor` is the component which processes the outbox and publishes messages/events to Kafka, once it could
 obtain the lock. If it could not obtain the lock on startup, it will continuously monitor the lock and try to obtain
 it (in case the lock-holding instance crashed or could not longer refresh the lock).

```java
@Configuration
@ComponentScan(basePackages = "one.tomorrow.transactionaloutbox")
public class TransactionalOutboxConfig {

    @Bean
    public OutboxProcessor outboxProcessor(OutboxRepository repository,
                                           Duration processingInterval,
                                           Duration outboxLockTimeout,
                                           String lockOwnerId,
                                           String eventSource,
                                           Map<String, Object> producerProps,
                                           AutowireCapableBeanFactory beanFactory) {
        return new OutboxProcessor(
                repository,
                new DefaultKafkaProducerFactory(producerProps),
                processingInterval,
                outboxLockTimeout,
                lockOwnerId,
                eventSource,
                beanFactory
        );
    }

}
```

* `OutboxLockService`: is instantiated by Spring, autowiring the `Duration` with `@Qualifier("outboxLockTimeout")`
* `OutboxRepository`: can be instantiated by Spring, only asking for a Hibernate `SessionFactory`
* `Duration processingInterval`: the interval to wait after the outbox was processed completely before it's processed
   again. This value should be significantly smaller than `outboxLockTimeout` (described next). If it's higher, this is still not an issue,
   then another instance might take over the lock in the meantime (after `outboxLockTimeout` has been exceeded) and process
   the outbox.
* `Duration outboxLockTimeout`: the time after that a lock should be considered to be timed out
  * a lock can be taken over by another instance only after that time had passed without a lock refresh by the lock owner
  * the chosen value should be higher than the 99%ile of gc pauses; but even if you'd use a smaller value (and lock would
    often get lost due to gc pauses) the library would still work correctly
  * the chosen value should be smaller than the max message publishing delay that you'd like to see (e.g. in deployment scenarios)
* `String lockOwnerId`: used to identify the instance trying to obtain the lock, must be unique per instance (you could e.g.
   use the hostname)
* `String eventSource`: used as value for the `x-source` header set for a message published to Kafka
* `Map<String, Object> producerProps`: the properties used to create the `KafkaProducer` (contains e.g. `bootstrap.servers` etc)
* `AutowireCapableBeanFactory beanFactory`: used to create the lock service (`OutboxLockService`)

## Usage

In a service that changes the database (inside a transaction), create and serialize the message/event that should
be published to Kafka transactionally (i.e. only if the current transaction could be committed). This could look like this:

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
    outboxService.saveForPublishing("some-topic", id, event);
}
```
