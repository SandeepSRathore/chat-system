package com.chatsystem.chat.message;

import com.chatsystem.common.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The Kafka producer is created lazily, so without this the first message after a chat server
 * starts waits seconds for broker metadata and an idempotent producer id before it can be ACKed.
 */
@Component
public class KafkaProducerWarmup {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerWarmup.class);

    private final KafkaTemplate<String, String> kafka;

    public KafkaProducerWarmup(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    /** Creates the producer and fetches the sync queue's metadata; failures only delay the first send. */
    public void warmUp() {
        long start = System.nanoTime();
        try {
            int partitions = kafka.partitionsFor(Topics.MESSAGE_SYNC).size();
            log.info("Kafka producer ready: {} partitions of {} in {} ms", partitions, Topics.MESSAGE_SYNC,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Kafka producer warm-up failed; the first message will connect on demand", e);
        }
    }
}
