package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.QueryLog;
import com.ragwiki.wiki.store.WikiStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 提问日志写入。
 *
 * <p>日志不能反过来拖慢主链路，所以走「入队 + 单独一个消费者线程落库」：
 * 调用方只做一次入队就返回，不受数据库抖动影响。
 * 队列不设上限——日志丢一条就少一份统计数据，宁可多占点内存也不静默丢弃。
 */
@Component
public class QueryLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(QueryLogRecorder.class);

    private final WikiStore store;
    private final BlockingQueue<QueryLog> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread consumer;

    public QueryLogRecorder(WikiStore store) {
        this.store = store;
    }

    @PostConstruct
    void start() {
        consumer = Thread.ofVirtual().name("query-log-writer").start(this::drain);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (consumer != null) {
            consumer.interrupt();
        }
    }

    public void record(String question, String channel, boolean hit, String entryId,
                       long latencyMillis, Integer tokenIn, Integer tokenOut, Long conversationId) {
        if (question == null) {
            return;
        }
        String trimmed = question.length() > 500 ? question.substring(0, 500) : question;
        Instant now = Instant.now();

        QueryLog entry = new QueryLog();
        entry.setTs(now);
        entry.setQuestion(trimmed);
        entry.setQuestionHash(FastPathText.sha256(FastPathText.normalize(trimmed)));
        entry.setChannel(channel);
        entry.setHit(hit);
        entry.setEntryId(entryId);
        entry.setLatencyMs((int) Math.min(latencyMillis, Integer.MAX_VALUE));
        entry.setTokenIn(tokenIn);
        entry.setTokenOut(tokenOut);
        entry.setConversationId(conversationId);
        entry.setCreatedAt(now);

        queue.offer(entry);
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                QueryLog entry = queue.take();
                store.insertQueryLog(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 落库失败不能把消费者打死，否则后面所有日志都积在队列里
                log.error("提问日志落库失败，丢弃这一条", e);
            }
        }
    }
}
