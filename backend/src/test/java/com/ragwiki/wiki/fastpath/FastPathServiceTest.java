package com.ragwiki.wiki.fastpath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.wiki.api.FastPathApi;
import com.ragwiki.wiki.dto.FastEntryView;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快通道的读路径：缓存命中与回落、生效期过滤、命中直答与日志记录。
 *
 * <p>Redis 用 mock：这里要验的是「缓存挂了也必须从库里拿到数据」，不是 Redis 本身。
 */
class FastPathServiceTest {

    private static final FastPathProperties PROPERTIES = new FastPathProperties(
            "aftersale", Duration.ofMinutes(5), 0.8, 0.9, 0.6,
            new FastPathProperties.Distill(3, 7));

    private final WikiStore store = mock(WikiStore.class);
    private final QueryLogRecorder recorder = mock(QueryLogRecorder.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final FastPathService service = new FastPathService(
            store, new FastPathMatcher(PROPERTIES), PROPERTIES, recorder, redis, objectMapper);

    private static FastEntry entry(String question, String answer, FastEntryStatus status,
                                   double confidence, LocalDate effectiveAt, LocalDate expiresAt) {
        FastEntry entry = new FastEntry();
        entry.setEntryId(FastPathText.entryId(question));
        entry.setScene("aftersale");
        entry.setQuestion(question);
        entry.setAnswer(answer);
        entry.setKeywords(FastEntry.keywordsJson(List.of("退货运费")));
        entry.setConfidence(BigDecimal.valueOf(confidence));
        entry.setStatus(status);
        entry.setOrigin(FastEntry.ORIGIN_DERIVED);
        entry.setSourcePageId("运费规则");
        entry.setEffectiveAt(effectiveAt);
        entry.setExpiresAt(expiresAt);
        entry.setHitCount(0);
        return entry;
    }

    @Test
    void entriesReadFromCacheWhenAvailable() throws Exception {
        FastEntryView cached = new FastEntryView("fp-1", "aftersale", "运费补贴上限是多少？",
                "单笔最高补贴 20 元。", List.of("运费补贴"), 0.9, "ACTIVE", "DERIVED",
                "运费补贴上限", "运费补贴上限", 3);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ragwiki:fastpath:entries:aftersale"))
                .thenReturn(objectMapper.writeValueAsString(List.of(cached)));

        List<FastEntryView> views = service.entries(null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).answer()).isEqualTo("单笔最高补贴 20 元。");
        verify(store, never()).listFastEntries(anyString(), any());
    }

    @Test
    void entriesFallBackToDatabaseWhenCacheFails() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(store.listFastEntries("aftersale", FastEntryStatus.ACTIVE)).thenReturn(List.of(
                entry("运费补贴上限是多少？", "单笔最高补贴 20 元。", FastEntryStatus.ACTIVE,
                        0.9, LocalDate.now().minusDays(1), null)));

        List<FastEntryView> views = service.entries("aftersale");

        assertThat(views).hasSize(1);
        assertThat(views.get(0).question()).isEqualTo("运费补贴上限是多少？");
    }

    @Test
    void entriesExcludeNotYetEffectiveAndExpired() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        LocalDate today = LocalDate.now();
        when(store.listFastEntries("aftersale", FastEntryStatus.ACTIVE)).thenReturn(List.of(
                entry("生效中问题", "答案", FastEntryStatus.ACTIVE, 0.9, today.minusDays(1), null),
                entry("未来生效问题", "答案", FastEntryStatus.ACTIVE, 0.9, today.plusDays(3), null),
                entry("已过期问题", "答案", FastEntryStatus.ACTIVE, 0.9, today.minusDays(30), today.minusDays(1))));

        List<FastEntryView> views = service.entries("aftersale");

        assertThat(views).extracting(FastEntryView::question).containsExactly("生效中问题");
    }

    @Test
    void answersByKeywordAndCountsHit() {
        FastEntry hit = entry("退货运费谁承担？", "质量问题由商家承担。", FastEntryStatus.ACTIVE,
                0.9, LocalDate.now().minusDays(1), null);
        hit.setId(7L);
        hit.setSourcePageId("运费规则");
        when(store.listFastEntries("aftersale", FastEntryStatus.ACTIVE)).thenReturn(List.of(hit));
        when(store.findPage("运费规则")).thenReturn(Optional.empty());

        FastPathApi.Answer answer = service.answer("退货运费谁出");

        assertThat(answer.hit()).isTrue();
        assertThat(answer.answer()).isEqualTo("质量问题由商家承担。");
        assertThat(answer.confidence()).isEqualTo(0.9);
        verify(store).incrementHitCount(7L);
        verify(recorder).record(eq("退货运费谁出"), eq("FASTPATH"), eq(true), eq(hit.getEntryId()),
                org.mockito.ArgumentMatchers.anyLong(), eq(null), eq(null), eq(null));
    }

    @Test
    void missIsRecordedAndDoesNotTouchHitCount() {
        when(store.listFastEntries("aftersale", FastEntryStatus.ACTIVE)).thenReturn(List.of());

        FastPathApi.Answer answer = service.answer("怎么申请营业执照");

        assertThat(answer.hit()).isFalse();
        verify(store, never()).incrementHitCount(any());
        verify(recorder).record(eq("怎么申请营业执照"), eq("FASTPATH"), eq(false), eq(null),
                org.mockito.ArgumentMatchers.anyLong(), eq(null), eq(null), eq(null));
    }

    @Test
    void sourceTitleComesFromTheSourceWikiPage() {
        FastEntry hit = entry("退货运费谁承担？", "质量问题由商家承担。", FastEntryStatus.ACTIVE,
                0.9, LocalDate.now().minusDays(1), null);
        hit.setId(8L);
        hit.setSourcePageId("运费规则");
        WikiPage source = new WikiPage();
        source.setPageId("运费规则");
        source.setTitle("运费收取与承担规则");
        source.setStatus(WikiPageStatus.ACTIVE);
        when(store.listFastEntries("aftersale", FastEntryStatus.ACTIVE)).thenReturn(List.of(hit));
        when(store.findPage("运费规则")).thenReturn(Optional.of(source));

        FastPathApi.Answer answer = service.answer("退货运费谁出");

        assertThat(answer.source()).isEqualTo("运费收取与承担规则");
    }

    @Test
    void evictCacheRemovesEverySceneKey() {
        when(redis.keys("ragwiki:fastpath:entries:*"))
                .thenReturn(java.util.Set.of("ragwiki:fastpath:entries:aftersale"));

        service.evictCache();

        verify(redis).delete(any(java.util.Collection.class));
    }
}
