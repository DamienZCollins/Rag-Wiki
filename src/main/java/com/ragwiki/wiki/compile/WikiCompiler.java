package com.ragwiki.wiki.compile;

import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.markdown.MarkdownSource;
import com.ragwiki.common.markdown.MarkdownSourceReader;
import com.ragwiki.wiki.dto.CompileReport;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.SourceDoc;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wiki 编译：把源文档切成概念条目，归并到已有条目或新建，最后落盘 + 入库 + 建链。
 *
 * <p>离线批处理，不在在线问答链路上。
 */
@Service
public class WikiCompiler {

    private static final Logger log = LoggerFactory.getLogger(WikiCompiler.class);

    private final WikiStore store;
    private final GitPageStore pageStore;
    private final CompileModel compileModel;
    private final LinkBuilder linkBuilder;
    private final RetirementDetector retirementDetector;
    private final IncrementalPlanner planner;
    private final Path policyDir;

    public WikiCompiler(WikiStore store,
                        GitPageStore pageStore,
                        CompileModel compileModel,
                        LinkBuilder linkBuilder,
                        RetirementDetector retirementDetector,
                        IncrementalPlanner planner,
                        @Value("${ragwiki.corpus.policy-dir:corpus/policies}") String policyDir) {
        this.store = store;
        this.pageStore = pageStore;
        this.compileModel = compileModel;
        this.linkBuilder = linkBuilder;
        this.retirementDetector = retirementDetector;
        this.planner = planner;
        this.policyDir = Path.of(policyDir);
    }

    /**
     * 全量或增量编译。
     *
     * @param incremental true 时只重算输入变化的文档，false 时全部重算
     */
    public CompileReport compileAll(boolean incremental) {
        long startedAt = System.currentTimeMillis();
        compileModel.resetUsage();

        List<SourceDoc> scanned = scan();
        // 规划必须在写库之前：先落库的话哈希已经被自己覆盖，比对永远判成没变化
        Map<String, SourceDoc> previous = index(store.listSourceDocs());
        Map<String, SourceDoc> known = index(scanned);

        List<SourceDoc> toCompile;
        List<SourceDoc> skipped;
        List<String> notes = new ArrayList<>();
        if (incremental) {
            IncrementalPlanner.Plan plan = planner.plan(scanned, previous);
            toCompile = plan.toCompile();
            skipped = new ArrayList<>(plan.skipped());
            notes.addAll(plan.notes());
        } else {
            toCompile = scanned.stream().filter(SourceDoc::isActive).toList();
            skipped = scanned.stream().filter(doc -> !doc.isActive()).toList();
            notes.add("全量编译，只处理生效文档");
        }

        register(scanned);
        Result result = compileDocuments(toCompile, known, notes);

        // 来源文档全部失效的条目在编译之后才判：新版本重新确立过的条目此时已经挂上了生效来源，
        // 不该被当成「来源全失效」处理
        List<RetirementDetector.RetiredPage> retired = new ArrayList<>(retirementDetector.retireOrphaned(scanned));
        retired.addAll(result.retiredPages());
        return buildReport(toCompile, skipped, notes, result, retired, startedAt);
    }

    /** 编译指定的一篇。允许编译已失效文档，用于复现历史状态 */
    public CompileReport compileOne(String docId) {
        long startedAt = System.currentTimeMillis();
        compileModel.resetUsage();

        List<SourceDoc> scanned = scan();
        Map<String, SourceDoc> known = index(scanned);
        register(scanned);
        SourceDoc target = known.get(docId);
        if (target == null) {
            throw new com.ragwiki.common.exception.BusinessException(
                    com.ragwiki.common.result.ResultCode.NOT_FOUND, "源文档不存在：" + docId);
        }

        List<String> notes = new ArrayList<>();
        notes.add("单篇编译：" + docId + "（状态 " + target.getStatus() + "）");

        Result result = compileDocuments(List.of(target), known, notes);
        return buildReport(List.of(target), List.of(), notes, result, result.retiredPages(), startedAt);
    }

    // ---------- 内部 ----------

    private record Result(List<String> createdPages,
                          List<String> updatedPages,
                          List<RetirementDetector.RetiredPage> retiredPages,
                          List<String> failedDocs) {
    }

    private Result compileDocuments(List<SourceDoc> docs, Map<String, SourceDoc> known, List<String> notes) {
        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<RetirementDetector.RetiredPage> retired = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (SourceDoc doc : docs) {
            String body = readBody(doc);
            Map<String, CompileModel.ExistingEntry> snapshot = existingSnapshot();

            List<ExtractedEntry> entries;
            try {
                entries = compileModel.extract(doc, body, List.copyOf(snapshot.values()));
            } catch (BusinessException e) {
                // 单篇失败不拖垮整批：这条已落地的条目都是完整的，重跑一遍即可补上
                log.error("编译文档失败，跳过：{}", doc.getDocId(), e);
                failed.add(doc.getDocId() + "：" + e.getMessage());
                notes.add("失败：" + doc.getDocId());
                continue;
            }

            // 先算出这一批要落到哪些条目上，再判失效：
            // 新版本重新确立的概念应该被更新，不该被当成「被取代的旧条目」失效掉
            Set<String> touched = new LinkedHashSet<>();
            for (ExtractedEntry entry : entries) {
                String title = StringUtils.hasText(entry.mergeInto()) ? entry.mergeInto() : entry.title();
                if (StringUtils.hasText(title)) {
                    touched.add(WikiPage.pageIdOf(title.strip()));
                }
            }
            if (StringUtils.hasText(doc.getSupersedes())) {
                retired.addAll(retirementDetector.retireSupersededBy(
                        doc.getSupersedes(), doc.getDocId(), touched));
            }

            for (ExtractedEntry entry : entries) {
                if (!StringUtils.hasText(entry.title()) && !StringUtils.hasText(entry.mergeInto())) {
                    log.warn("模型返回的条目既没有标题也没有归并目标，跳过：{}", entry);
                    continue;
                }
                ApplyOutcome outcome = applyEntry(entry, doc, known);
                if (outcome == ApplyOutcome.CREATED) {
                    created.add(entry.title());
                } else if (outcome == ApplyOutcome.UPDATED) {
                    updated.add(entry.title());
                }
            }
        }
        return new Result(List.copyOf(created), List.copyOf(updated), List.copyOf(retired), List.copyOf(failed));
    }

    private enum ApplyOutcome {
        CREATED, UPDATED, SKIPPED
    }

    /** 已有生效条目的快照，带摘要与适用条件，供模型判断归并目标 */
    private Map<String, CompileModel.ExistingEntry> existingSnapshot() {
        Map<String, CompileModel.ExistingEntry> snapshot = new LinkedHashMap<>();
        for (WikiPage page : store.listPages(WikiPageStatus.ACTIVE)) {
            PageContent content = pageStore.read(page.getPageId()).orElse(PageContent.empty());
            snapshot.put(page.getTitle(), new CompileModel.ExistingEntry(
                    page.getTitle(), page.getVersion(), content.summary(), content.conditions()));
        }
        return snapshot;
    }

    /** 把这个候选条目归并进已有条目或新建 */
    private ApplyOutcome applyEntry(ExtractedEntry entry, SourceDoc doc, Map<String, SourceDoc> known) {
        // 标题归一化后再用：模型容易把版本号写进标题，写进库就再也归并不上了
        String title = WikiPage.normalizeTitle(
                StringUtils.hasText(entry.mergeInto()) ? entry.mergeInto() : entry.title());
        String pageId = WikiPage.pageIdOf(title);

        Optional<WikiPage> existing = store.findPage(pageId);
        if (existing.isPresent() && existing.get().getStatus() == WikiPageStatus.RETIRED) {
            log.warn("条目 {} 已失效，不自动恢复；来源 {} 带来的内容已跳过，需要人工确认后再启用",
                    title, doc.getDocId());
            return ApplyOutcome.SKIPPED;
        }

        Instant now = Instant.now();
        boolean created = existing.isEmpty();

        List<String> sources = new ArrayList<>();
        List<String> related = new ArrayList<>();
        if (!created) {
            sources.addAll(existing.get().sourceList());
            pageStore.read(pageId).ifPresent(content -> related.addAll(content.relatedTitles()));
        }
        if (!sources.contains(doc.getDocId())) {
            sources.add(doc.getDocId());
        }
        // 关联标题同样要归一化：模型会在标题里加版本号，带进文件就成了一条指向不存在条目的链接
        for (String relatedTitle : entry.related()) {
            if (!StringUtils.hasText(relatedTitle)) {
                continue;
            }
            String normalized = WikiPage.normalizeTitle(relatedTitle);
            if (!normalized.equals(title) && !related.contains(normalized)) {
                related.add(normalized);
            }
        }

        PageContent content = new PageContent(
                entry.summary() == null ? "" : entry.summary(),
                entry.conditions(),
                entry.exceptions(),
                related);

        WikiPage page = existing.orElseGet(WikiPage::new);
        if (created) {
            page.setPageId(pageId);
            page.setStatus(WikiPageStatus.ACTIVE);
            page.setCreatedAt(now);
        }
        page.setTitle(title);
        page.setVersion(newerVersion(page.getVersion(), doc.getVersion()));
        page.setEffectiveAt(newerDate(page.getEffectiveAt(), doc.getEffectiveAt()));
        page.setSources(WikiPage.sourcesJson(sources));
        page.setInputHash(inputHashOf(sources, known));
        page.setUpdatedAt(now);

        if (created) {
            store.insertPage(page);
        } else {
            store.updatePage(page);
        }

        pageStore.write(page, content);
        linkBuilder.rebuild(page, content);
        linkBuilder.resolveIncoming(page);

        known.putIfAbsent(doc.getDocId(), doc);
        return created ? ApplyOutcome.CREATED : ApplyOutcome.UPDATED;
    }

    private String inputHashOf(List<String> docIds, Map<String, SourceDoc> known) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String docId : docIds) {
            SourceDoc source = known.get(docId);
            fingerprints.put(docId, source == null
                    ? "unknown"
                    : source.getContentHash() + "@" + source.getVersion());
        }
        return IncrementalPlanner.inputHash(fingerprints);
    }

    private void register(List<SourceDoc> docs) {
        for (SourceDoc doc : docs) {
            store.upsertSourceDoc(doc);
        }
    }

    /** 只读语料，不写库；写库交给 register，好让增量规划先看到上一轮的哈希 */
    private List<SourceDoc> scan() {
        List<MarkdownSource> sources = MarkdownSourceReader.readDirectory(policyDir);
        List<SourceDoc> docs = new ArrayList<>(sources.size());
        for (MarkdownSource source : sources) {
            SourceDoc doc = new SourceDoc();
            doc.setDocId(source.id());
            doc.setTitle(source.title());
            doc.setCategory(source.category());
            doc.setVersion(source.version());
            doc.setEffectiveAt(parseDate(source.effectiveAt()));
            doc.setStatus(StringUtils.hasText(source.status()) ? source.status() : SourceDoc.STATUS_ACTIVE);
            doc.setSupersedes(source.supersedes());
            doc.setSupersededBy(source.supersededBy());
            doc.setContentHash(source.contentHash());
            doc.setPath(policyDir.relativize(source.path()).toString());

            // 正文不入库，编译时按路径重读
            doc.setSupersededBy(resolveSupersededBy(doc.getDocId(), sources));
            docs.add(doc);
        }
        return docs;
    }

    /** 语料里只有 supersedes 方向，反方向的 superseded_by 在这里补出来 */
    private String resolveSupersededBy(String docId, List<MarkdownSource> sources) {
        return sources.stream()
                .filter(source -> docId.equals(source.supersedes()))
                .map(MarkdownSource::id)
                .findFirst()
                .orElse(null);
    }

    private String readBody(SourceDoc doc) {
        Path file = policyDir.resolve(doc.getPath());
        return MarkdownSourceReader.read(file, "doc_id").body();
    }

    private static Map<String, SourceDoc> index(List<SourceDoc> docs) {
        Map<String, SourceDoc> map = new LinkedHashMap<>();
        for (SourceDoc doc : docs) {
            map.put(doc.getDocId(), doc);
        }
        return map;
    }

    private static LocalDate parseDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String newerVersion(String current, String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return current;
        }
        if (!StringUtils.hasText(current)) {
            return candidate;
        }
        return candidate.compareTo(current) >= 0 ? candidate : current;
    }

    private static LocalDate newerDate(LocalDate current, LocalDate candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private CompileReport buildReport(List<SourceDoc> compiled,
                                      List<SourceDoc> skipped,
                                      List<String> notes,
                                      Result result,
                                      List<RetirementDetector.RetiredPage> retired,
                                      long startedAt) {
        List<String> created = result.createdPages();
        List<String> updated = result.updatedPages();
        List<String> failed = result.failedDocs();

        long elapsed = System.currentTimeMillis() - startedAt;
        CompileModel.Usage usage = compileModel.usage();
        CompileReport report = new CompileReport(
                compiled.stream().map(SourceDoc::getDocId).toList(),
                skipped.stream().map(SourceDoc::getDocId).toList(),
                List.copyOf(notes),
                created.size(),
                updated.size(),
                retired.size(),
                failed,
                created,
                updated,
                retired.stream().map(RetirementDetector.RetiredPage::title).toList(),
                elapsed,
                usage.calls(),
                usage.promptTokens(),
                usage.completionTokens());
        log.info("编译完成：新建 {} / 更新 {} / 失效 {} / 失败 {}，耗时 {}ms，模型 {} 次调用 / {} token",
                report.created(), report.updated(), report.retired(), failed.size(), elapsed,
                usage.calls(), usage.totalTokens());
        return report;
    }
}
