package com.ragwiki.eval.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.eval.entity.EvalCase;
import com.ragwiki.eval.mapper.EvalCaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 用例导入。源文件是唯一事实源，数据不合法要直接报错——评测数据错了，后面所有数字都不可信。
 */
class CaseImporterTest {

    private static final String POLICY_CASE = """
            {"case_id":"policy-001","type":"POLICY","question":"退货的邮费谁掏",\
            "expected_answer_points":[["商家承担","卖家承担"]],\
            "expected_sources":["运费收取与承担规则"],\
            "expected_doc_ids":["policy-freight-rule-v2025"],\
            "expect_refusal":false,"expect_tools":["search_knowledge"],\
            "expected_tool_args":{},"forbidden_points":[],"attribution_layer":"RECALL"}\
            """;

    private static final String REFUSAL_CASE = """
            {"case_id":"refusal-001","type":"REFUSAL","question":"怎么申请营业执照",\
            "expected_answer_points":[],"expected_sources":[],"expected_doc_ids":[],\
            "expect_refusal":true,"expect_tools":[],"expected_tool_args":{},\
            "forbidden_points":[],"attribution_layer":"FALLBACK"}\
            """;

    @TempDir
    Path caseDir;

    private final EvalCaseMapper caseMapper = mock(EvalCaseMapper.class);

    private CaseImporter importer() {
        return new CaseImporter(caseMapper, new ObjectMapper(), caseDir.toString());
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.write(caseDir.resolve(fileName), List.of(lines), StandardCharsets.UTF_8);
    }

    @Test
    void importsEveryJsonlSortedByFileName() throws Exception {
        write("policy.jsonl", POLICY_CASE);
        write("refusal.jsonl", REFUSAL_CASE);

        CaseImporter.ImportReport report = importer().importAll();

        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.byType()).containsEntry("POLICY", 1).containsEntry("REFUSAL", 1);
        assertThat(report.files()).containsExactly("policy.jsonl(1)", "refusal.jsonl(1)");
        // 先清旧快照再写，表里就是源文件的当前内容
        verify(caseMapper).delete(any());
        verify(caseMapper, times(2)).insert(any(EvalCase.class));
    }

    @Test
    void mapsFieldsAndSerializesStructuredValues() throws Exception {
        write("policy.jsonl", POLICY_CASE);

        importer().importAll();

        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseMapper).insert(captor.capture());
        EvalCase imported = captor.getValue();
        assertThat(imported.getCaseId()).isEqualTo("policy-001");
        assertThat(imported.getCaseType()).isEqualTo(EvalCase.TYPE_POLICY);
        assertThat(imported.getQuestion()).isEqualTo("退货的邮费谁掏");
        assertThat(imported.getExpectedAnswerPoints()).contains("商家承担");
        assertThat(imported.getExpectedDocIds()).contains("policy-freight-rule-v2025");
        assertThat(imported.getExpectRefusal()).isFalse();
        assertThat(imported.getExpectTools()).contains("search_knowledge");
        assertThat(imported.getSourceFile()).isEqualTo("policy.jsonl");
    }

    @Test
    void blankLinesAreSkipped() throws Exception {
        write("policy.jsonl", "", POLICY_CASE, "   ", "");

        assertThat(importer().importAll().imported()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateCaseIdAcrossFiles() throws Exception {
        write("policy.jsonl", POLICY_CASE);
        write("adversarial.jsonl", POLICY_CASE);

        assertThatThrownBy(() -> importer().importAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("case_id 重复");

        verify(caseMapper, never()).delete(any());
        verify(caseMapper, never()).insert(any(EvalCase.class));
    }

    @Test
    void rejectsInvalidJsonWithLineNumber() throws Exception {
        write("policy.jsonl", POLICY_CASE, "{不是 JSON");

        assertThatThrownBy(() -> importer().importAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy.jsonl 第 2 行");
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        write("policy.jsonl", """
                {"type":"POLICY","question":"问题","expected_answer_points":[]}\
                """);

        assertThatThrownBy(() -> importer().importAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少字段 case_id");
    }

    @Test
    void rejectsUnknownCaseType() throws Exception {
        write("policy.jsonl", """
                {"case_id":"x-1","type":"UNKNOWN","question":"问题","expected_answer_points":[]}\
                """);

        assertThatThrownBy(() -> importer().importAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type 不合法");
    }

    @Test
    void rejectsMissingCaseDirectory() {
        CaseImporter importer = new CaseImporter(caseMapper, new ObjectMapper(),
                caseDir.resolve("not-created").toString());

        assertThatThrownBy(importer::importAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用例目录不存在");
    }

    @Test
    void rejectsEmptyCaseDirectory() {
        assertThatThrownBy(() -> importer().importAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有 jsonl 文件");
    }
}
