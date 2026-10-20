package com.ragwiki.wiki.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.model.ChatClient;
import com.ragwiki.common.model.dto.ChatCompletion;
import com.ragwiki.wiki.schema.SourceDoc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编译模型这一层的输出解析：模型经常套代码块、返回空内容或被截断，解析必须能分辨并给出可排查的报错。
 */
class CompileModelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 按顺序回放固定内容的模型替身，同时记下调用次数 */
    private static ChatClient replaying(AtomicInteger calls, String... contents) {
        List<String> queue = List.of(contents);
        return (messages, tools, onTextDelta) -> {
            int index = calls.getAndIncrement();
            String content = queue.get(Math.min(index, queue.size() - 1));
            return new ChatCompletion(content, List.of(), "stop");
        };
    }

    private static SourceDoc doc(String docId, String title) {
        SourceDoc doc = new SourceDoc();
        doc.setDocId(docId);
        doc.setTitle(title);
        doc.setVersion("v2025.1");
        return doc;
    }

    @Test
    void extractParsesEntriesFromPlainJson() {
        CompileModel model = new CompileModel(replaying(new AtomicInteger(), """
                {"entries":[{"title":"运费补贴上限","mergeInto":"","summary":"单笔最高 20 元。",\
                "conditions":["普通快递"],"exceptions":[],"related":["运费规则"]}]}\
                """), objectMapper);

        List<ExtractedEntry> entries = model.extract(doc("policy-a", "运费政策"), "正文", List.of());

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).title()).isEqualTo("运费补贴上限");
        assertThat(entries.get(0).conditions()).containsExactly("普通快递");
        assertThat(entries.get(0).related()).containsExactly("运费规则");
    }

    @Test
    void extractStripsMarkdownFenceAndSurroundingProse() {
        CompileModel model = new CompileModel(replaying(new AtomicInteger(), """
                以下是结果：
                ```json
                {"entries":[{"title":"退款时效","summary":"1-3 个工作日。","conditions":[],"exceptions":[],"related":[]}]}
                ```
                如需调整请告诉我。
                """), objectMapper);

        List<ExtractedEntry> entries = model.extract(doc("policy-b", "退款政策"), "正文", List.of());

        assertThat(entries).extracting(ExtractedEntry::title).containsExactly("退款时效");
    }

    @Test
    void extractRejectsEmptyContent() {
        CompileModel model = new CompileModel(replaying(new AtomicInteger(), ""), objectMapper);

        assertThatThrownBy(() -> model.extract(doc("policy-c", "空输出"), "正文", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("空内容"));
    }

    @Test
    void extractRejectsResponseWithoutEntriesArray() {
        CompileModel model = new CompileModel(replaying(new AtomicInteger(), """
                {"result":"没有抽到条目"}
                """), objectMapper);

        assertThatThrownBy(() -> model.extract(doc("policy-d", "缺字段"), "正文", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("entries"));
    }

    @Test
    void usageAccumulatesAndResets() {
        AtomicInteger calls = new AtomicInteger();
        CompileModel model = new CompileModel(replaying(calls, """
                {"entries":[]}
                """), objectMapper);

        model.extract(doc("policy-e", "空条目"), "正文", List.of());
        model.extract(doc("policy-e", "空条目"), "正文", List.of());

        assertThat(model.usage().calls()).isEqualTo(2);
        model.resetUsage();
        assertThat(model.usage().calls()).isZero();
    }

    @Test
    void judgeConflictsSkipsModelWhenNoCandidates() {
        AtomicInteger calls = new AtomicInteger();
        CompileModel model = new CompileModel(replaying(calls, "{\"conflicts\":[]}"), objectMapper);

        assertThat(model.judgeConflicts(List.of())).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void judgeConflictsBatchesAndKeepsGlobalPairNumbers() {
        AtomicInteger calls = new AtomicInteger();
        CompileModel model = new CompileModel(replaying(calls,
                // 第一批返回第 1 对冲突
                "{\"conflicts\":[{\"pair\":1,\"reason\":\"补贴上限不一致\"}]}",
                // 第二批返回第 1 对冲突，要换算成整体编号 6
                "{\"conflicts\":[{\"pair\":1,\"reason\":\"时效不一致\"}]}"), objectMapper);

        List<CompileModel.ConflictCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates.add(new CompileModel.ConflictCandidate(
                    "条目标题" + i, "摘要", "另一条目标题" + i, "摘要"));
        }

        List<CompileModel.Conflict> conflicts = model.judgeConflicts(candidates);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(conflicts).extracting(CompileModel.Conflict::pair).containsExactly(1, 6);
        assertThat(conflicts).extracting(CompileModel.Conflict::reason)
                .containsExactly("补贴上限不一致", "时效不一致");
    }

    @Test
    void judgeConflictsTreatsUnparsableBatchAsNoConflict() {
        AtomicInteger calls = new AtomicInteger();
        CompileModel model = new CompileModel(replaying(calls, "模型跑题了，没有 JSON"), objectMapper);

        List<CompileModel.Conflict> conflicts = model.judgeConflicts(List.of(
                new CompileModel.ConflictCandidate("A", "摘要", "B", "摘要")));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void stripToJsonObjectHandlesFenceProseAndMissingBraces() {
        assertThat(CompileModel.stripToJsonObject("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(CompileModel.stripToJsonObject("前缀 {\"a\":{\"b\":2}} 后缀"))
                .isEqualTo("{\"a\":{\"b\":2}}");
        assertThat(CompileModel.stripToJsonObject("没有对象")).isNull();
        assertThat(CompileModel.stripToJsonObject(null)).isNull();
    }
}
