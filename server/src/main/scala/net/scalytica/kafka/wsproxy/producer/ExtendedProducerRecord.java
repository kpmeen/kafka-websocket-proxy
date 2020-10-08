package net.scalytica.kafka.wsproxy.producer;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

/**
 * Custom extension of {@link ProducerRecord} that allows creating an instance
 * with the correct types using the weaker type system in Java.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class ExtendedProducerRecord<K, V> extends ProducerRecord<K, V> {

  public ExtendedProducerRecord(String topic, K key, V value, Iterable<Header> headers) {
    super(topic, null, null, key, value, headers);
  }

  public ExtendedProducerRecord(String topic, V value, Iterable<Header> headers) {
    super(topic, null, null, null, value, headers);
  }

}
