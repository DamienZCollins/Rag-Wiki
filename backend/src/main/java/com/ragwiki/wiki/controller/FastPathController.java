package com.ragwiki.wiki.controller;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.Result;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.wiki.api.FastPathApi;
import com.ragwiki.wiki.dto.FastEntryView;
import com.ragwiki.wiki.fastpath.EntryPublisher;
import com.ragwiki.wiki.fastpath.FastEntryDeriver;
import com.ragwiki.wiki.fastpath.FastPathService;
import com.ragwiki.wiki.fastpath.TrafficDistiller;
import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.store.WikiStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "快通道", description = "确定性直答：零模型调用、零向量检索，未命中由 Agent 转 RAG")
@RestController
@RequestMapping("/api/fastpath")
public class FastPathController {

    private final FastPathService fastPathService;
    private final FastEntryDeriver deriver;
    private final EntryPublisher publisher;
    private final TrafficDistiller distiller;
    private final WikiStore store;

    public FastPathController(FastPathService fastPathService,
                              FastEntryDeriver deriver,
                              EntryPublisher publisher,
                              TrafficDistiller distiller,
                              WikiStore store) {
        this.fastPathService = fastPathService;
        this.deriver = deriver;
        this.publisher = publisher;
        this.distiller = distiller;
        this.store = store;
    }

    @Operation(summary = "场景候选选项", description = "页面初始化直查，只返回生效且在生效期内的条目，走 Redis 缓存")
    @GetMapping("/entries")
    public Result<List<FastEntryView>> entries(
            @RequestParam(value = "scene", required = false) String scene) {
        return Result.ok(fastPathService.entries(scene));
    }

    @Operation(summary = "快通道作答", description = """
            传 entryId 表示用户点了候选选项；传 question 表示自由输入。
            命中返回 hit=true 与答案，未命中返回 hit=false 由调用方转 RAG。""")
    @PostMapping("/answer")
    public Result<AnswerResponse> answer(@Valid @RequestBody FastPathAnswerRequest request) {
        if (StringUtils.hasText(request.entryId())) {
            FastEntry entry = store.findFastEntry(request.entryId().strip())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND,
                            "快通道条目不存在：" + request.entryId()));
            String sourceTitle = entry.getSourcePageId() == null ? null
                    : store.findPage(entry.getSourcePageId()).map(page -> page.getTitle()).orElse(null);
            return Result.ok(new AnswerResponse(true, entry.getEntryId(), entry.getQuestion(),
                    entry.getAnswer(), entry.getConfidence().doubleValue(), sourceTitle));
        }

        FastPathApi.Answer answer = fastPathService.answer(request.question());
        return Result.ok(new AnswerResponse(answer.hit(), answer.entryId(), answer.question(),
                answer.answer(), answer.confidence(), answer.source()));
    }

    @Operation(summary = "从 Wiki 条目派生候选", description = "纯规则、零模型调用；重复执行幂等")
    @PostMapping("/entries/derive")
    public Result<FastEntryDeriver.Report> derive() {
        FastEntryDeriver.Report report = deriver.derive();
        fastPathService.evictCache();
        return Result.ok(report);
    }

    @Operation(summary = "上线校验并发布", description = """
            先下线来源 Wiki 条目已失效的生效条目，再把通过校验的候选上线。
            校验是强制的：没有来源、来源不存在或已失效、没有关键词的候选都上不了线。""")
    @PostMapping("/entries/publish")
    public Result<EntryPublisher.PublishReport> publish() {
        EntryPublisher.PublishReport report = publisher.publish();
        fastPathService.evictCache();
        return Result.ok(report);
    }

    @Operation(summary = "流量沉淀", description = "把慢通道被反复问到的问题捞进候选池，重复执行幂等")
    @PostMapping("/distill")
    public Result<TrafficDistiller.Report> distill() {
        return Result.ok(distiller.distill());
    }

    @Operation(summary = "候选池", description = "供人工审核：沉淀出来的候选需要人工绑定来源")
    @GetMapping("/entries/candidates")
    public Result<List<FastEntryView>> candidates(
            @RequestParam(value = "status", required = false, defaultValue = "CANDIDATE") String status) {
        FastEntryStatus parsed;
        try {
            parsed = FastEntryStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "status 只能是 CANDIDATE / ACTIVE / OFFLINE");
        }
        return Result.ok(store.listFastEntries(parsed).stream()
                .map(entry -> new FastEntryView(
                        entry.getEntryId(), entry.getScene(), entry.getQuestion(), entry.getAnswer(),
                        entry.keywordList(), entry.getConfidence().doubleValue(),
                        entry.getStatus().name(), entry.getOrigin(), entry.getSourcePageId(),
                        entry.getSourcePageId() == null ? null
                                : store.findPage(entry.getSourcePageId()).map(page -> page.getTitle()).orElse(null),
                        entry.getHitCount()))
                .toList());
    }

    public record FastPathAnswerRequest(String entryId, String question) {

        @jakarta.validation.constraints.AssertTrue(
                message = "entryId 与 question 至少要给一个")
        public boolean isProvided() {
            return StringUtils.hasText(entryId) || StringUtils.hasText(question);
        }

        @NotBlank(message = "不能为空")
        public String questionOrEmpty() {
            return question == null ? "" : question;
        }
    }

    public record AnswerResponse(boolean hit, String entryId, String question, String answer,
                                 double confidence, String source) {
    }
}
