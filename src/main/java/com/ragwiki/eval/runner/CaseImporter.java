package com.ragwiki.eval.runner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.eval.entity.EvalCase;
import com.ragwiki.eval.mapper.EvalCaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 把 eval/cases/*.jsonl 导入 eval_case 表。
 *
 * <p>源文件是唯一事实源，表只是导入快照：每次导入先按 source_file 清掉旧快照再写，
 * 保证表里就是源文件的当前内容。用例改了没重新导入，跑出来的还是旧用例。
 *
 * <p>导入前做校验：case_id 重复、必填字段缺失、类型不合法都会直接报错，
 * 不静默跳过——评测数据出错，后面所有数字都不可信。
 */
@Service
public class CaseImporter {

    private static final Logger log = LoggerFactory.getLogger(CaseImporter.class);

    private final EvalCaseMapper caseMapper;
    private final ObjectMapper objectMapper;
    private final Path caseDir;

    public CaseImporter(EvalCaseMapper caseMapper,
                        ObjectMapper objectMapper,
                        @Value("${ragwiki.eval.case-dir:eval/cases}") String caseDir) {
        this.caseMapper = caseMapper;
        this.objectMapper = objectMapper;
        this.caseDir = Path.of(caseDir);
    }

    public record ImportReport(int imported, Map<String, Integer> byType, List<String> files) {
    }

    /** 导入目录下全部 jsonl，按文件名排序 */
    public ImportReport importAll() {
        if (!Files.isDirectory(caseDir)) {
            throw new IllegalStateException("用例目录不存在：" + caseDir.toAbsolutePath());
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(caseDir)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读取用例目录失败：" + caseDir, e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("用例目录下没有 jsonl 文件：" + caseDir.toAbsolutePath());
        }

        List<EvalCase> parsed = new ArrayList<>();
        List<String> imported = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            List<EvalCase> cases = readFile(file, fileName);
            for (EvalCase evalCase : cases) {
                Integer previous = seen.put(evalCase.getCaseId(), 1);
                if (previous != null) {
                    throw new IllegalStateException("case_id 重复：" + evalCase.getCaseId()
                            + "，出现在 " + fileName);
                }
                parsed.add(evalCase);
            }
            imported.add(fileName + "(" + cases.size() + ")");
        }

        // 先删旧快照再写：表里就是源文件的当前内容，不残留已删掉的用例
        caseMapper.delete(Wrappers.<EvalCase>lambdaQuery().isNotNull(EvalCase::getId));
        for (EvalCase evalCase : parsed) {
            caseMapper.insert(evalCase);
        }

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (EvalCase evalCase : parsed) {
            byType.merge(evalCase.getCaseType(), 1, Integer::sum);
        }
        log.info("用例导入完成：{} 条，分布 {}", parsed.size(), byType);
        return new ImportReport(parsed.size(), byType, List.copyOf(imported));
    }

    private List<EvalCase> readFile(Path file, String fileName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取用例文件失败：" + file, e);
        }

        List<EvalCase> cases = new ArrayList<>();
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, Object> raw;
            try {
                raw = objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException(
                        "%s 第 %d 行不是合法 JSON：%s".formatted(fileName, lineNumber, e.getMessage()), e);
            }
            cases.add(toEntity(raw, fileName, lineNumber));
        }
        return cases;
    }

    private EvalCase toEntity(Map<String, Object> raw, String fileName, int lineNumber) {
        EvalCase evalCase = new EvalCase();
        evalCase.setCaseId(requireString(raw, "case_id", fileName, lineNumber));
        String type = requireString(raw, "type", fileName, lineNumber);
        if (!List.of(EvalCase.TYPE_POLICY, EvalCase.TYPE_ORDER,
                EvalCase.TYPE_REFUSAL, EvalCase.TYPE_ADVERSARIAL).contains(type)) {
            throw new IllegalStateException("%s 第 %d 行的 type 不合法：%s"
                    .formatted(fileName, lineNumber, type));
        }
        evalCase.setCaseType(type);
        evalCase.setQuestion(requireString(raw, "question", fileName, lineNumber));
        evalCase.setExpectedAnswerPoints(json(raw.getOrDefault("expected_answer_points", List.of())));
        evalCase.setExpectedSources(json(raw.getOrDefault("expected_sources", List.of())));
        evalCase.setExpectedDocIds(json(raw.getOrDefault("expected_doc_ids", List.of())));
        evalCase.setExpectRefusal(Boolean.TRUE.equals(raw.get("expect_refusal")));
        evalCase.setExpectTools(json(raw.getOrDefault("expect_tools", List.of())));
        evalCase.setExpectedToolArgs(json(raw.getOrDefault("expected_tool_args", Map.of())));
        evalCase.setForbiddenPoints(json(raw.getOrDefault("forbidden_points", List.of())));
        evalCase.setAttributionLayer((String) raw.get("attribution_layer"));
        evalCase.setNote((String) raw.get("note"));
        evalCase.setSourceFile(fileName);
        evalCase.setCreatedAt(Instant.now());
        return evalCase;
    }

    private String requireString(Map<String, Object> raw, String key, String fileName, int lineNumber) {
        Object value = raw.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("%s 第 %d 行缺少字段 %s"
                    .formatted(fileName, lineNumber, key));
        }
        return value.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化用例字段失败", e);
        }
    }
}
