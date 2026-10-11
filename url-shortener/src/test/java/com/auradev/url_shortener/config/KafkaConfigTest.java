package com.auradev.url_shortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executor gửi click phải xả hết hàng đợi khi app dừng (SIGTERM lúc deploy), nếu không các click đang chờ
 * trong bộ nhớ sẽ mất.
 */
class KafkaConfigTest {

    @Test
    void theClickExecutorIsDestroyedWithCloseSoQueuedEventsAreNotDropped() throws Exception {
        Bean bean = KafkaConfig.class
                .getMethod("clickPublishExecutor", ClickProperties.class)
                .getAnnotation(Bean.class);

        // "shutdown" (mặc định cũ) không chờ; "close" chờ các task đã xếp hàng chạy xong
        assertThat(bean.destroyMethod()).isEqualTo("close");
    }

    @Test
    void closingTheExecutorRunsEveryQueuedTask() {
        ClickProperties properties = new ClickProperties();
        properties.setPublisherThreads(1);
        properties.setPublisherQueueSize(10);
        ExecutorService executor = new KafkaConfig().clickPublishExecutor(properties);
        AtomicInteger sent = new AtomicInteger();

        for (int i = 0; i < 6; i++) {
            executor.execute(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sent.incrementAndGet();
            });
        }
        executor.close();

        assertThat(sent).hasValue(6);
        assertThat(executor.isTerminated()).isTrue();
    }
}
