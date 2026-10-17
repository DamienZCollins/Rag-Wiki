package com.ragwiki.eval.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.Result;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.eval.entity.EvalResult;
import com.ragwiki.eval.entity.EvalRun;
import com.ragwiki.eval.mapper.EvalResultMapper;
import com.ragwiki.eval.mapper.EvalRunMapper;
import com.ragwiki.eval.runner.CaseImporter;
import com.ragwiki.eval.runner.EvalRunner;
import com.ragwiki.eval.runner.RegressionGate;
import com.ragwiki.rag.api.IndexControlApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测接口。跑批次是同步的：一批上百条用例要几十分钟，HTTP 长连接等不住，
 * 但这一版不做任务队列——评测是本地离线动作，跑完直接看结果比引入异步状态更省事。
 * 接口会阻塞到批次结束。
 */
@Tag(name = "评测", description = "批量跑用例、分层归因、对比实验与回归门禁")
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final CaseImporter caseImporter;
    private final EvalRunner runner;
    private final RegressionGate gate;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final IndexControlApi indexControlApi;
    private final ObjectMapper objectMapper;

    public EvalController(CaseImporter caseImporter,
                          EvalRunner runner,
                          RegressionGate gate,
                          EvalRunMapper runMapper,
                          EvalResultMapper resultMapper,
                          IndexControlApi indexControlApi,
                          ObjectMapper objectMapper) {
        this.caseImporter = caseImporter;
        this.runner = runner;
        this.gate = gate;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.indexControlApi = indexControlApi;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "导入用例", description = """
            把 eval/cases/*.jsonl 导入 eval_case 表，先清旧快照再写。
            源文件是唯一事实源，改了用例必须重新导入才会生效。""")
    @PostMapping("/cases/import")
    public Result<CaseImporter.ImportReport> importCases() {
        return Result.ok(caseImporter.importAll());
    }

    @Operation(summary = "跑评测批次", description = """
            同步执行，会阻塞到批次结束。用例从 eval_case 表读，跑之前先导入。
            caseTypes 为空表示跑全部；取值 POLICY / ORDER / REFUSAL / ADVERSARIAL。
            批次配置（索引、模型、阈值）在开始时快照进 eval_run.config。""")
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody(required = false) RunRequest request) {
        RunRequest effective = request == null ? new RunRequest(null, List.of()) : request;
        String label = effective.label() == null || effective.label().isBlank()
                ? "未命名批次" : effective.label();
        List<String> types = effective.caseTypes() == null ? List.of() : effective.caseTypes();
        EvalRun run = runner.run(label, types);
        return Result.ok(toRunView(run));
    }

    @Operation(summary = "续跑批次", description = """
            接着一个没跑完的批次继续跑。每条用例跑完即落库，续跑只补没跑的那些，
            汇总时把两段合起来算。已完成的批次不允许续跑。""")
    @PostMapping("/resume/{runId}")
    public Result<Map<String, Object>> resume(@PathVariable("runId") String runId) {
        return Result.ok(toRunView(runner.resume(runId)));
    }

    @Operation(summary = "批次列表", description = "按开始时间倒序，用于选批次做对比")
    @GetMapping("/runs")
    public Result<List<Map<String, Object>>> runs() {
        List<EvalRun> runs = runMapper.selectList(Wrappers.<EvalRun>lambdaQuery()
                .orderByDesc(EvalRun::getStartedAt).last("LIMIT 50"));
        return Result.ok(runs.stream().map(this::toRunView).toList());
    }

    @Operation(summary = "批次报告", description = "指标 + 归因分布 + 失败用例清单")
    @GetMapping("/report/{runId}")
    public Result<Map<String, Object>> report(@PathVariable("runId") String runId) {
        EvalRun run = findRun(runId);
        List<EvalResult> results = resultMapper.selectList(Wrappers.<EvalResult>lambdaQuery()
                .eq(EvalResult::getRunId, runId)
                .orderByAsc(EvalResult::getCaseId));

        List<Map<String, Object>> failures = results.stream()
                .filter(result -> Boolean.FALSE.equals(result.getPassed()))
                .map(result -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("caseId", result.getCaseId());
                    view.put("layer", result.getAttributionLayer());
                    view.put("channel", result.getChannel());
                    view.put("error", result.getError());
                    view.put("detail", readJson(result.getDetail()));
                    return view;
                })
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("run", toRunView(run));
        report.put("metrics", readJson(run.getMetrics()));
        report.put("attribution", readJson(run.getAttribution()));
        report.put("failures", failures);
        return Result.ok(report);
    }

    @Operation(summary = "逐条结果", description = "某个批次的全部用例结果，含判定明细")
    @GetMapping("/results/{runId}")
    public Result<List<Map<String, Object>>> results(@PathVariable("runId") String runId) {
        findRun(runId);
        List<EvalResult> results = resultMapper.selectList(Wrappers.<EvalResult>lambdaQuery()
                .eq(EvalResult::getRunId, runId)
                .orderByAsc(EvalResult::getCaseId));
        return Result.ok(results.stream().map(result -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("caseId", result.getCaseId());
            view.put("passed", result.getPassed());
            view.put("channel", result.getChannel());
            view.put("layer", result.getAttributionLayer());
            view.put("latencyMs", result.getLatencyMs());
            view.put("tokenIn", result.getTokenIn());
            view.put("tokenOut", result.getTokenOut());
            view.put("detail", readJson(result.getDetail()));
            return view;
        }).toList());
    }

    @Operation(summary = "写入基线", description = "把某个批次的比率型指标固化为回归门禁的对照")
    @PostMapping("/baseline/{runId}")
    public Result<RegressionGate.Baseline> writeBaseline(@PathVariable("runId") String runId) {
        EvalRun run = findRun(runId);
        Map<String, Object> metrics = readJson(run.getMetrics());
        RegressionGate.Baseline baseline = new RegressionGate.Baseline(
                run.getRunId(), run.getLabel(), gate.pickGuarded(metrics));
        gate.writeBaseline(baseline);
        return Result.ok(baseline);
    }

    @Operation(summary = "回归门禁", description = """
            拿基线比某个批次。只比比率型指标，容差默认 0.02。
            任一关键指标掉出容差就判失败，并列出该批次的失败用例供定位。""")
    @GetMapping("/gate/{runId}")
    public Result<RegressionGate.GateReport> gate(@PathVariable("runId") String runId) {
        EvalRun run = findRun(runId);
        List<EvalResult> failed = resultMapper.selectList(Wrappers.<EvalResult>lambdaQuery()
                .eq(EvalResult::getRunId, runId)
                .eq(EvalResult::getPassed, false)
                .orderByAsc(EvalResult::getCaseId));
        List<String> failedCases = failed.stream().map(EvalResult::getCaseId).toList();
        return Result.ok(gate.compare(readJson(run.getMetrics()), failedCases));
    }

    @Operation(summary = "切换索引源", description = """
            把别名指到指定物理索引。索引源对照实验用：
            探针与在线问答都走别名，切完别名两边测的就是同一个索引。""")
    @PostMapping("/index/switch")
    public Result<Map<String, Object>> switchIndex(@RequestBody IndexSwitchRequest request) {
        indexControlApi.switchAlias(request.physicalIndex());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("aliasTarget", indexControlApi.currentAliasTarget());
        view.put("physicalIndices", indexControlApi.physicalIndices());
        return Result.ok(view);
    }

    @Operation(summary = "当前索引状态", description = "别名指向与现存物理索引，对照实验前后核对用")
    @GetMapping("/index/status")
    public Result<Map<String, Object>> indexStatus() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("aliasTarget", indexControlApi.currentAliasTarget());
        view.put("physicalIndices", indexControlApi.physicalIndices());
        return Result.ok(view);
    }

    private EvalRun findRun(String runId) {
        EvalRun run = runMapper.selectOne(Wrappers.<EvalRun>lambdaQuery().eq(EvalRun::getRunId, runId));
        if (run == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批次不存在：" + runId);
        }
        return run;
    }

    private Map<String, Object> toRunView(EvalRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", run.getRunId());
        view.put("label", run.getLabel());
        view.put("caseTypes", run.getCaseTypes());
        view.put("caseCount", run.getCaseCount());
        view.put("passed", run.getPassed());
        view.put("failed", run.getFailed());
        view.put("status", run.getStatus());
        view.put("latencyMs", run.getLatencyMs());
        view.put("config", readJson(run.getConfig()));
        view.put("metrics", readJson(run.getMetrics()));
        view.put("attribution", readJson(run.getAttribution()));
        view.put("startedAt", run.getStartedAt());
        return view;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of("_parseError", e.getMessage());
        }
    }

    public record RunRequest(String label, List<String> caseTypes) {
    }

    public record IndexSwitchRequest(String physicalIndex) {
    }
}
