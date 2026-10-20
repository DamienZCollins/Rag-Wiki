package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.mapper.QueryLogMapper;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.store.WikiStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流量沉淀：慢通道高频问题进候选池，但没有来源，只能停在候选态。
 */
class TrafficDistillerTest {

    private static final FastPathProperties PROPERTIES = new FastPathProperties(
            "aftersale", Duration.ofMinutes(5), 0.8, 0.9, 0.6,
            new FastPathProperties.Distill(3, 7));

    private final WikiStore store = mock(WikiStore.class);
    private final QueryLogMapper queryLogMapper = mock(QueryLogMapper.class);
    private final TrafficDistiller distiller = new TrafficDistiller(
            store, queryLogMapper, new FastPathLexicon(), PROPERTIES);

    private static Map<String, Object> row(String question, long hits) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("question", question);
        row.put("question_hash", FastPathText.sha256(FastPathText.normalize(question)));
        row.put("hits", hits);
        return row;
    }

    @Test
    void distillsFrequentQuestionIntoCandidateWithoutSource() {
        when(queryLogMapper.selectMaps(any())).thenReturn(List.of(row("大闸蟹签收后能退吗", 5L)));
        when(store.findFastEntry(any())).thenReturn(Optional.empty());

        TrafficDistiller.Report report = distiller.distill();

        assertThat(report.frequentQuestions()).isEqualTo(1);
        assertThat(report.created()).isEqualTo(1);

        ArgumentCaptor<FastEntry> captor = ArgumentCaptor.forClass(FastEntry.class);
        verify(store).insertFastEntry(captor.capture());
        FastEntry entry = captor.getValue();
        assertThat(entry.getQuestion()).isEqualTo("大闸蟹签收后能退吗");
        assertThat(entry.getAnswer()).isEmpty();
        assertThat(entry.getSourcePageId()).isNull();
        assertThat(entry.getStatus()).isEqualTo(FastEntryStatus.CANDIDATE);
        assertThat(entry.getOrigin()).isEqualTo(FastEntry.ORIGIN_DISTILLED);
        // 置信度低于命中阈值：没有依据的答案不能直答出去
        assertThat(entry.getConfidence()).isLessThan(java.math.BigDecimal.valueOf(PROPERTIES.minConfidence()));
        assertThat(entry.getEntryId()).isEqualTo(FastPathText.entryId("大闸蟹签收后能退吗"));
    }

    @Test
    void isIdempotentWhenCandidateAlreadyExists() {
        when(queryLogMapper.selectMaps(any())).thenReturn(List.of(row("大闸蟹签收后能退吗", 5L)));
        when(store.findFastEntry(FastPathText.entryId("大闸蟹签收后能退吗")))
                .thenReturn(Optional.of(new FastEntry()));

        TrafficDistiller.Report report = distiller.distill();

        assertThat(report.created()).isZero();
        assertThat(report.skippedQuestions()).containsExactly("大闸蟹签收后能退吗");
        verify(store, never()).insertFastEntry(any());
    }

    @Test
    void questionWithoutDomainTermsFallsBackToItselfAsKeyword() {
        when(queryLogMapper.selectMaps(any())).thenReturn(List.of(row("你们周末上班吗", 4L)));
        when(store.findFastEntry(any())).thenReturn(Optional.empty());

        distiller.distill();

        ArgumentCaptor<FastEntry> captor = ArgumentCaptor.forClass(FastEntry.class);
        verify(store).insertFastEntry(captor.capture());
        // 没命中任何术语时用问题本身当关键词，至少还能靠精确匹配生效
        assertThat(captor.getValue().keywordList()).containsExactly("你们周末上班吗");
    }
}
