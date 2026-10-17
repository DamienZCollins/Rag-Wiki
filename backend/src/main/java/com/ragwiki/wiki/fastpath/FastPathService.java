package com.ragwiki.wiki.fastpath;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.wiki.api.FastPathApi;
import com.ragwiki.wiki.dto.FastEntryView;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.QueryLog;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 快通道：场景候选、命中直答、日志记录。
 *
 * <p>候选列表走 Redis 缓存，缓存读不到就回落数据库——缓存是加速手段，不是唯一来源，
 * 缓存挂了也必须有数据返回。
 */
@Service
public class FastPathService implements FastPathApi {

    private static final Logger log = LoggerFactory.getLogger(FastPathService.class);
    private static final String CACHE_KEY_PREFIX = "ragwiki:fastpath:entries:";

    private final WikiStore store;
    private final FastPathMatcher matcher;
    private final FastPathProperties properties;
    private final QueryLogRecorder recorder;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public FastPathService(WikiStore store,
                           FastPathMatcher matcher,
                           FastPathProperties properties,
                           QueryLogRecorder recorder,
                           StringRedisTemplate redis,
                           ObjectMapper objectMapper) {
        this.store = store;
        this.matcher = matcher;
        this.properties = properties;
        this.recorder = recorder;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 场景初始化：只返回生效且在生效期内的条目，零模型调用、零向量检索 */
    public List<FastEntryView> entries(String scene) {
        String effectiveScene = scene == null || scene.isBlank() ? properties.scene() : scene;
        String cacheKey = CACHE_KEY_PREFIX + effectiveScene;

        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<FastEntryView>>() {
                });
            }
        } catch (Exception e) {
            log.warn("快通道缓存读取失败，回落数据库：{}", e.getMessage());
        }

        List<FastEntryView> views = loadFromDatabase(effectiveScene);
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(views), properties.cacheTtl());
        } catch (Exception e) {
            log.warn("快通道缓存写入失败，不影响本次返回：{}", e.getMessage());
        }
        return views;
    }

    /** 淘汰缓存。条目内容变化后调用 */
    public void evictCache() {
        try {
            var keys = redis.keys(CACHE_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("清理快通道缓存失败，等 TTL 自然过期：{}", e.getMessage());
        }
    }

    @Override
    public Answer answer(String question) {
        long startedAt = System.nanoTime();
        List<FastEntry> candidates = store.listFastEntries(properties.scene(), FastEntryStatus.ACTIVE);
        FastPathMatcher.MatchResult result = matcher.match(question, candidates);
        long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000;

        if (!result.hit()) {
            recorder.record(question, QueryLog.CHANNEL_FASTPATH, false, null, latencyMillis, null, null, null);
            return Answer.miss();
        }

        FastEntry entry = result.entry();
        try {
            store.incrementHitCount(entry.getId());
        } catch (Exception e) {
            log.warn("命中计数更新失败，不影响本次回答 entryId={}", entry.getEntryId(), e);
        }
        recorder.record(question, QueryLog.CHANNEL_FASTPATH, true, entry.getEntryId(),
                latencyMillis, null, null, null);

        String sourceTitle = entry.getSourcePageId() == null ? null
                : store.findPage(entry.getSourcePageId()).map(page -> page.getTitle()).orElse(null);
        return new Answer(true, entry.getEntryId(), entry.getQuestion(), entry.getAnswer(),
                entry.getConfidence().doubleValue(), sourceTitle);
    }

    @Override
    public void recordSlowPath(String question, long latencyMillis, int tokenIn, int tokenOut, Long conversationId) {
        recorder.record(question, QueryLog.CHANNEL_RAG, false, null, latencyMillis,
                tokenIn, tokenOut, conversationId);
    }

    private List<FastEntryView> loadFromDatabase(String scene) {
        LocalDate today = LocalDate.now();
        return store.listFastEntries(scene, FastEntryStatus.ACTIVE).stream()
                .filter(entry -> entry.getEffectiveAt() == null || !entry.getEffectiveAt().isAfter(today))
                .filter(entry -> entry.getExpiresAt() == null || !entry.getExpiresAt().isBefore(today))
                .map(this::toView)
                .toList();
    }

    private FastEntryView toView(FastEntry entry) {
        String sourceTitle = entry.getSourcePageId() == null ? null
                : store.findPage(entry.getSourcePageId()).map(page -> page.getTitle()).orElse(null);
        return new FastEntryView(
                entry.getEntryId(),
                entry.getScene(),
                entry.getQuestion(),
                entry.getAnswer(),
                entry.keywordList(),
                entry.getConfidence().doubleValue(),
                entry.getStatus().name(),
                entry.getOrigin(),
                entry.getSourcePageId(),
                sourceTitle,
                entry.getHitCount());
    }
}
