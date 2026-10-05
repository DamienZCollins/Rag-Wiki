package com.ragwiki.wiki.controller;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.result.Result;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.wiki.compile.WikiCompiler;
import com.ragwiki.wiki.dto.CompileReport;
import com.ragwiki.wiki.dto.CompileRequest;
import com.ragwiki.wiki.dto.LintReport;
import com.ragwiki.wiki.dto.WikiPageSummary;
import com.ragwiki.wiki.lint.WikiLinter;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiLink;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "LLM Wiki", description = "把源文档编译成互链的结构化条目，并提供 lint 体检")
@RestController
@RequestMapping("/api/wiki")
public class WikiController {

    private final WikiCompiler compiler;
    private final WikiLinter linter;
    private final WikiStore store;
    private final GitPageStore pageStore;

    public WikiController(WikiCompiler compiler,
                          WikiLinter linter,
                          WikiStore store,
                          GitPageStore pageStore) {
        this.compiler = compiler;
        this.linter = linter;
        this.store = store;
        this.pageStore = pageStore;
    }

    @Operation(summary = "编译条目", description = """
            离线批处理，不在在线问答链路上。
            不给 sourceDocId 就编译全部生效文档；incremental 控制只重算输入变化的文档。
            报告里给出新建 / 更新 / 失效 / 跳过的条目与文档，便于核对数字与实际改动。""")
    @PostMapping("/compile")
    public Result<CompileReport> compile(@RequestBody(required = false) CompileRequest request) {
        CompileRequest effective = request == null ? new CompileRequest(null, true) : request;
        if (StringUtils.hasText(effective.sourceDocId())) {
            return Result.ok(compiler.compileOne(effective.sourceDocId().strip()));
        }
        return Result.ok(compiler.compileAll(effective.incrementalOrDefault()));
    }

    @Operation(summary = "条目体检", description = "矛盾、过期、孤页、待创建，按严重程度分级，可定位到具体条目")
    @PostMapping("/lint")
    public Result<LintReport> lint() {
        return Result.ok(linter.lint());
    }

    @Operation(summary = "条目列表", description = "可按状态过滤，默认只列生效条目")
    @GetMapping("/pages")
    public Result<List<WikiPageSummary>> pages(
            @RequestParam(value = "status", required = false, defaultValue = "ACTIVE") String status) {
        List<WikiPage> pages = "ALL".equalsIgnoreCase(status)
                ? store.listPages()
                : store.listPages(parseStatus(status));
        return Result.ok(pages.stream().map(this::toSummary).toList());
    }

    @Operation(summary = "条目详情", description = "含正文、出链与反向链接")
    @GetMapping("/pages/{pageId}")
    public Result<WikiPageSummary.WikiPageDetail> page(@PathVariable("pageId") String pageId) {
        WikiPage page = store.findPage(pageId).orElseThrow(() ->
                new BusinessException(ResultCode.NOT_FOUND, "条目不存在：" + pageId));

        PageContent content = pageStore.read(pageId).orElse(PageContent.empty());
        List<WikiPageSummary.Link> links = store.listLinksFrom(pageId).stream().map(this::toLink).toList();
        List<WikiPageSummary.Link> backlinks = store.listLinksTo(pageId).stream()
                .map(link -> toBacklink(link))
                .toList();

        return Result.ok(new WikiPageSummary.WikiPageDetail(toSummary(page), content, links, backlinks));
    }

    private WikiPageSummary toSummary(WikiPage page) {
        return new WikiPageSummary(
                page.getPageId(),
                page.getTitle(),
                page.getVersion(),
                page.getStatus() == null ? null : page.getStatus().name(),
                page.getEffectiveAt(),
                page.getExpiresAt(),
                page.sourceList(),
                page.getUpdatedAt(),
                store.listLinksFrom(page.getPageId()).size(),
                store.listLinksTo(page.getPageId()).size());
    }

    private WikiPageSummary.Link toLink(WikiLink link) {
        return new WikiPageSummary.Link(link.getToPageId(), link.getToTitle(), link.getStatus());
    }

    private WikiPageSummary.Link toBacklink(WikiLink link) {
        return store.findPage(link.getFromPageId())
                .map(from -> new WikiPageSummary.Link(from.getPageId(), from.getTitle(),
                        WikiLink.STATUS_ACTIVE))
                .orElseGet(() -> new WikiPageSummary.Link(link.getFromPageId(), link.getFromPageId(),
                        WikiLink.STATUS_PENDING));
    }

    private com.ragwiki.wiki.schema.WikiPageStatus parseStatus(String status) {
        try {
            return com.ragwiki.wiki.schema.WikiPageStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "status 只能是 DRAFT / ACTIVE / RETIRED / ALL");
        }
    }
}
