package com.auradev.url_shortener.config;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hạ tầng Kafka cho theo dõi click: khai báo topic, executor gửi event và container consumer theo lô.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /** Topic chính. Khai báo tường minh vì topic broker tự tạo chỉ có 1 partition. */
    @Bean
    public NewTopic clickTopic(ClickProperties properties) {
        return TopicBuilder.name(properties.getTopic())
                .partitions(properties.getPartitions())
                .replicas(properties.getReplicas())
                .build();
    }

    /** Dead-letter topic cho event không xử lý được; cùng số partition với topic chính. */
    @Bean
    public NewTopic clickDeadLetterTopic(ClickProperties properties) {
        return TopicBuilder.name(properties.getDeadLetterTopic())
                .partitions(properties.getPartitions())
                .replicas(properties.getReplicas())
                .build();
    }

    /**
     * Executor gửi event lên Kafka. Hàng đợi có giới hạn và <b>từ chối</b> khi đầy (ném
     * {@code RejectedExecutionException}, publisher đếm lại) thay vì chặn thread request.
     *
     * <p>{@code destroyMethod = "close"} (khác {@code shutdown}) chờ các event còn trong hàng đợi được gửi xong khi app
     * dừng (SIGTERM lúc deploy) thay vì bỏ chúng. Producer Kafka được tạo trước executor nên đóng sau nó và vẫn
     * gửi được trong lúc xả hàng đợi.
     */
    @Bean(name = "clickPublishExecutor", destroyMethod = "close")
    public ExecutorService clickPublishExecutor(ClickProperties properties) {
        int threads = Math.max(1, properties.getPublisherThreads());
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "click-publisher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getPublisherQueueSize())),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        // Mang theo observation hiện tại (trace của request redirect) sang thread gửi, để span Kafka là con
        // của request và consumer nối tiếp cùng một trace
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(pool, snapshots::captureAll);
    }

    /**
     * Container consumer theo lô. Lỗi hạ tầng (ví dụ DB) được thử lại với back-off có giới hạn, quá số
     * lần thì cả lô được đẩy sang dead-letter topic để partition không bị kẹt; do ghi DB là idempotent
     * nên lô có thể được xử lý lại từ DLT mà không đếm trùng.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> clickListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            ClickProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.setConcurrency(Math.max(1, properties.getConsumerConcurrency()));

        // partition = -1: để Kafka client tự chọn partition của DLT
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, ex) -> new TopicPartition(properties.getDeadLetterTopic(), -1));

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxElapsedTime(15_000L);
        backOff.setMaxInterval(5_000L);
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, backOff));
        return factory;
    }
}
