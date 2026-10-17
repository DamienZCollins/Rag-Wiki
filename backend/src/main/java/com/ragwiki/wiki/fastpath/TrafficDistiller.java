package com.ragwiki.wiki.fastpath;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ragwiki.wiki.mapper.QueryLogMapper;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.QueryLog;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 流量沉淀：把慢通道被反复问到的问题捞进快通道候选池。
 *
 * <p>沉淀出来的候选**没有来源**，只能停在候选态——必须人工绑定 Wiki 条目并通过上线校验才会生效。
 * 这是刻意的：高频不等于有依据，让一个没有出处的答案直答出去，比慢一点更糟。
 *
 * <p>条目 id 由问题文本决定，重复执行写的是同一个 id，所以是幂等的。
 */
@Component
public class TrafficDistiller {

    private static final Logger log = LoggerFactory.getLogger(TrafficDistiller.class);

    private final WikiStore store;
    private final QueryLogMapper queryLogMapper;
    private final FastPathLexicon lexicon;
    private final FastPathProperties properties;

    public TrafficDistiller(WikiStore store,
                            QueryLogMapper queryLogMapper,
                            FastPathLexicon lexicon,
                            FastPathProperties properties) {
        this.store = store;
        this.queryLogMapper = queryLogMapper;
        this.lexicon = lexicon;
        this.properties = properties;
    }

    public record Report(int windowDays, int minHits, int frequentQuestions, int created,
                         List<String> createdQuestions, List<String> skippedQuestions) {
    }

    public Report distill() {
        int windowDays = properties.distill().windowDays();
        int minHits = properties.distill().minHits();
        Instant windowStart = Instant.now().minus(windowDays, ChronoUnit.DAYS);

        QueryWrapper<QueryLog> query = new QueryWrapper<>();
        query.select("question_hash", "MIN(question) AS question", "COUNT(*) AS hits")
                .eq("channel", QueryLog.CHANNEL_RAG)
                .ge("ts", windowStart)
                .groupBy("question_hash")
                .having("COUNT(*) >= " + minHits);
        List<Map<String, Object>> rows = queryLogMapper.selectMaps(query);

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            String question = String.valueOf(row.get("question"));
            String entryId = FastPathText.entryId(question);
            if (store.findFastEntry(entryId).isPresent()) {
                skipped.add(question);
                continue;
            }

            FastEntry entry = new FastEntry();
            entry.setEntryId(entryId);
            entry.setScene(properties.scene());
            entry.setQuestion(question);
            entry.setAnswer("");
            entry.setKeywords(FastEntry.keywordsJson(lexicon.extractFromQuestion(question)));
            entry.setConfidence(BigDecimal.valueOf(properties.distilledConfidence()));
            entry.setStatus(FastEntryStatus.CANDIDATE);
            entry.setOrigin(FastEntry.ORIGIN_DISTILLED);
            entry.setSourcePageId(null);
            entry.setHitCount(0);
            Instant now = Instant.now();
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            store.insertFastEntry(entry);
            created.add(question);
        }

        log.info("流量沉淀完成：窗口 {} 天、阈值 {} 次，高频问题 {} 个，新入候选 {} 个",
                windowDays, minHits, rows.size(), created.size());
        return new Report(windowDays, minHits, rows.size(), created.size(),
                List.copyOf(created), List.copyOf(skipped));
    }
}
