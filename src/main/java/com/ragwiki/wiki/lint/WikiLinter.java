package com.ragwiki.wiki.lint;

import com.ragwiki.common.model.EmbeddingClient;
import com.ragwiki.wiki.WikiProperties;
import com.ragwiki.wiki.compile.CompileModel;
import com.ragwiki.wiki.dto.LintReport;
import com.ragwiki.wiki.dto.LintReport.Kind;
import com.ragwiki.wiki.dto.LintReport.LintIssue;
import com.ragwiki.wiki.dto.LintReport.Severity;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiLink;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 条目体检：矛盾、过期、孤页、待创建。
 *
 * <p>矛盾检测先用向量相似度筛出相关条目对，再让编译模型判定是否真的冲突。
 * 两步是为了把模型的调用压到一次：条目上百时两两比对既贵又吵，
 * 而相似度筛选本身就过滤掉了绝大多数无关条目。
 */
@Service
public class WikiLinter {

    private static final Logger log = LoggerFactory.getLogger(WikiLinter.class);

    private final WikiStore store;
    private final GitPageStore pageStore;
    private final EmbeddingClient embeddingClient;
    private final CompileModel compileModel;
    private final WikiProperties properties;

    public WikiLinter(WikiStore store,
                      GitPageStore pageStore,
                      EmbeddingClient embeddingClient,
                      CompileModel compileModel,
                      WikiProperties properties) {
        this.store = store;
        this.pageStore = pageStore;
        this.embeddingClient = embeddingClient;
        this.compileModel = compileModel;
        this.properties = properties;
    }

    public LintReport lint() {
        List<WikiPage> pages = store.listPages();
        List<WikiPage> active = pages.stream().filter(page -> page.getStatus() == WikiPageStatus.ACTIVE).toList();

        List<LintIssue> conflicts = detectConflicts(active);
        List<LintIssue> expired = detectExpired(active);
        List<LintIssue> orphans = detectOrphans(active);
        List<LintIssue> pending = detectPendingLinks();

        log.info("lint 完成：条目 {} 个，矛盾 {} / 过期 {} / 孤页 {} / 待创建 {}",
                pages.size(), conflicts.size(), expired.size(), orphans.size(), pending.size());
        return new LintReport(pages.size(), conflicts, expired, orphans, pending);
    }

    // ---------- 矛盾 ----------

    private List<LintIssue> detectConflicts(List<WikiPage> active) {
        if (active.size() < 2) {
            return List.of();
        }

        Map<String, PageContent> contents = new LinkedHashMap<>();
        Map<String, List<Float>> vectors = new LinkedHashMap<>();
        for (WikiPage page : active) {
            PageContent content = pageStore.read(page.getPageId()).orElse(PageContent.empty());
            contents.put(page.getPageId(), content);
            vectors.put(page.getPageId(), toList(embeddingClient.embed(
                    page.getTitle() + "\n" + content.summary())));
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                WikiPage left = active.get(i);
                WikiPage right = active.get(j);
                double similarity = cosine(vectors.get(left.getPageId()), vectors.get(right.getPageId()));
                if (similarity < properties.lintSimilarityThreshold()) {
                    continue;
                }
                candidates.add(new Candidate(similarity, left, right,
                        new CompileModel.ConflictCandidate(
                                left.getTitle(), renderForJudgement(contents.get(left.getPageId())),
                                right.getTitle(), renderForJudgement(contents.get(right.getPageId())))));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 按相似度从高到低截断：越像的越可能真的冲突，超预算时先丢最不像的
        candidates.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        int limit = Math.min(candidates.size(), properties.lintMaxPairs());
        List<Candidate> selected = candidates.subList(0, limit);
        List<CompileModel.ConflictCandidate> payload = selected.stream()
                .map(Candidate::candidate).toList();

        List<CompileModel.Conflict> conflicts = compileModel.judgeConflicts(payload);

        List<LintIssue> issues = new ArrayList<>();
        for (CompileModel.Conflict conflict : conflicts) {
            int index = conflict.pair() - 1;
            if (index < 0 || index >= selected.size()) {
                log.warn("模型返回的条目对编号越界，忽略：{}", conflict.pair());
                continue;
            }
            Candidate pair = selected.get(index);
            issues.add(new LintIssue(Kind.CONFLICT, Severity.HIGH,
                    pair.left().getPageId(), pair.left().getTitle(), conflict.reason(),
                    pair.right().getPageId(), pair.right().getTitle()));
        }
        return issues;
    }

    private record Candidate(double similarity, WikiPage left, WikiPage right,
                             CompileModel.ConflictCandidate candidate) {
    }

    private static String renderForJudgement(PageContent content) {
        StringBuilder builder = new StringBuilder();
        builder.append("摘要：").append(content.summary().replace('\n', ' ')).append('\n');
        if (!content.conditions().isEmpty()) {
            builder.append("适用条件：").append(String.join("；", content.conditions())).append('\n');
        }
        if (!content.exceptions().isEmpty()) {
            builder.append("例外：").append(String.join("；", content.exceptions())).append('\n');
        }
        return builder.toString();
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float value : vector) {
            list.add(value);
        }
        return list;
    }

    private static double cosine(List<Float> left, List<Float> right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftNorm += left.get(i) * left.get(i);
            rightNorm += right.get(i) * right.get(i);
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    // ---------- 过期 ----------

    private List<LintIssue> detectExpired(List<WikiPage> active) {
        LocalDate today = LocalDate.now();
        List<LintIssue> issues = new ArrayList<>();
        for (WikiPage page : active) {
            if (page.getExpiresAt() != null && page.getExpiresAt().isBefore(today)) {
                issues.add(LintIssue.of(Kind.EXPIRED, Severity.HIGH, page.getPageId(), page.getTitle(),
                        "有效期已于 %s 截止，状态仍是已生效".formatted(page.getExpiresAt())));
                continue;
            }
            if (page.getEffectiveAt() != null && page.getEffectiveAt().isAfter(today)) {
                issues.add(LintIssue.of(Kind.EXPIRED, Severity.MEDIUM, page.getPageId(), page.getTitle(),
                        "生效期 %s 还没到，状态却是已生效".formatted(page.getEffectiveAt())));
            }
        }
        return issues;
    }

    // ---------- 孤页与待创建 ----------

    private List<LintIssue> detectOrphans(List<WikiPage> active) {
        Set<String> hasOutbound = new HashSet<>();
        Set<String> hasInbound = new HashSet<>();
        for (WikiLink link : store.listAllLinks()) {
            hasOutbound.add(link.getFromPageId());
            if (link.getToPageId() != null) {
                hasInbound.add(link.getToPageId());
            }
        }

        List<LintIssue> issues = new ArrayList<>();
        for (WikiPage page : active) {
            if (!hasOutbound.contains(page.getPageId()) && !hasInbound.contains(page.getPageId())) {
                issues.add(LintIssue.of(Kind.ORPHAN, Severity.LOW, page.getPageId(), page.getTitle(),
                        "没有任何条目指向它，它也不指向任何条目"));
            }
        }
        issues.sort(Comparator.comparing(LintIssue::pageId));
        return issues;
    }

    private List<LintIssue> detectPendingLinks() {
        List<LintIssue> issues = new ArrayList<>();
        for (WikiLink link : store.listAllLinks()) {
            if (link.getToPageId() != null) {
                continue;
            }
            store.findPage(link.getFromPageId()).ifPresent(from -> issues.add(LintIssue.of(
                    Kind.PENDING_LINK, Severity.LOW, from.getPageId(), from.getTitle(),
                    "引用了还不存在的条目《%s》".formatted(link.getToTitle()))));
        }
        return issues;
    }
}
