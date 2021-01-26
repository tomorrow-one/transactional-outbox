package one.tomorrow.transactionaloutbox.service;

import one.tomorrow.transactionaloutbox.service.OutboxProcessor.KafkaProducerFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.HashMap;
import java.util.Map;

import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION;
import static org.apache.kafka.clients.producer.ProducerConfig.RETRIES_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;

public class DefaultKafkaProducerFactory implements KafkaProducerFactory {

	private final HashMap<String, Object> producerProps;

	public DefaultKafkaProducerFactory(Map<String, Object> producerProps) {
		HashMap<String, Object> props = new HashMap<>(producerProps);
		// Fettings for dealing with broker failures - so that the producer.send returned future eventually fails
		// due to a timeoutexception and we can recreate it.
		// For preventing out-of-order messages in case of broker failures and internal producer retries, usually
		// the first 2 properties should be set to Int.MAX / Long.MAX - which we do not need so far because we're
		// sending record by record, without batching.
		setIfNotSet(props, RETRIES_CONFIG, 10);
		setIfNotSet(props, MAX_BLOCK_MS_CONFIG, 1000);
		setIfNotSet(props, MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
		// serializer settings
		props.put(KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		props.put(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		this.producerProps = props;
	}

	private static void setIfNotSet(Map<String, Object> props, String prop, Object value) {
		if (!props.containsKey(prop)) props.put(prop, value);
	}

	@Override
	public KafkaProducer<String, byte[]> createKafkaProducer() {
		return new KafkaProducer<>(producerProps);
	}

	@Override
	public String toString() {
		return "DefaultKafkaProducerFactory{producerProps=" + producerProps + '}';
	}

}
