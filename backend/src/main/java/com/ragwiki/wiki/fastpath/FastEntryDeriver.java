package com.ragwiki.wiki.fastpath;

import com.ragwiki.wiki.schema.FastEntry;
import com.ragwiki.wiki.schema.FastEntryStatus;
import com.ragwiki.wiki.schema.PageContent;
import com.ragwiki.wiki.schema.WikiPage;
import com.ragwiki.wiki.schema.WikiPageStatus;
import com.ragwiki.wiki.store.GitPageStore;
import com.ragwiki.wiki.store.WikiStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 从 Wiki 条目派生快通道候选条目。
 *
 * <p>纯规则、零模型调用：取条目的摘要（也就是「一句话结论」）当答案，
 * 标题套一个问句模板当问题，标题里命中的领域术语当关键词。
 *
 * <p>条目 id 由问题文本决定，重复派生写的是同一个 id，所以是幂等的。
 * 派生出来的是**候选**，还要过上线校验才能真正生效。
 */
@Component
public class FastEntryDeriver {

    private static final Logger log = LoggerFactory.getLogger(FastEntryDeriver.class);
    private static final int MIN_ANSWER_LENGTH = 8;

    private final WikiStore store;
    private final GitPageStore pageStore;
    private final FastPathLexicon lexicon;
    private final FastPathProperties properties;

    public FastEntryDeriver(WikiStore store,
                            GitPageStore pageStore,
                            FastPathLexicon lexicon,
                            FastPathProperties properties) {
        this.store = store;
        this.pageStore = pageStore;
        this.lexicon = lexicon;
        this.properties = properties;
    }

    public record Report(int scannedPages, int created, int skipped, List<String> createdEntries) {
    }

    public Report derive() {
        List<WikiPage> pages = store.listPages(WikiPageStatus.ACTIVE);
        int created = 0;
        int skipped = 0;
        List<String> createdEntries = new java.util.ArrayList<>();

        for (WikiPage page : pages) {
            PageContent content = pageStore.read(page.getPageId()).orElse(PageContent.empty());
            // 摘要本身就是条目的「一句话结论」，整段当答案；只取第一句会答得残缺
            String answer = content.summary().replace("\n", "").strip();
            if (answer.length() < MIN_ANSWER_LENGTH) {
                skipped++;
                continue;
            }

            String question = toQuestion(page.getTitle());
            String entryId = FastPathText.entryId(question);
            if (store.findFastEntry(entryId).isPresent()) {
                skipped++;
                continue;
            }

            FastEntry entry = new FastEntry();
            entry.setEntryId(entryId);
            entry.setScene(properties.scene());
            entry.setQuestion(question);
            entry.setAnswer(answer);
            entry.setKeywords(FastEntry.keywordsJson(lexicon.extract(page.getTitle())));
            entry.setConditions(content.conditions().isEmpty() ? null : String.join("；", content.conditions()));
            entry.setConfidence(java.math.BigDecimal.valueOf(properties.derivedConfidence()));
            entry.setStatus(FastEntryStatus.CANDIDATE);
            entry.setOrigin(FastEntry.ORIGIN_DERIVED);
            entry.setSourcePageId(page.getPageId());
            entry.setEffectiveAt(page.getEffectiveAt() == null ? LocalDate.now() : page.getEffectiveAt());
            entry.setHitCount(0);
            Instant now = Instant.now();
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);

            store.insertFastEntry(entry);
            created++;
            createdEntries.add(question);
        }

        log.info("快通道派生完成：扫描 {} 个条目，新建 {}，跳过 {}", pages.size(), created, skipped);
        return new Report(pages.size(), created, skipped, List.copyOf(createdEntries));
    }

    /** 标题是名词短语，套一个问句模板才像用户会问的话 */
    static String toQuestion(String title) {
        String trimmed = title.strip();
        String suffix;
        if (endsWithAny(trimmed, "上限", "额度", "金额", "门槛", "次数")) {
            suffix = "是多少？";
        } else if (endsWithAny(trimmed, "时效", "时限", "期限", "有效期")) {
            suffix = "是多久？";
        } else if (endsWithAny(trimmed, "规则", "方式", "流程", "标准", "要求", "约定", "划分",
                "情形", "范围", "判定", "处理", "限制", "说明", "服务", "定义", "标识")) {
            suffix = "是怎么规定的？";
        } else if (endsWithAny(trimmed, "范围", "条件", "举证", "例外")) {
            suffix = "有哪些？";
        } else {
            suffix = "是什么？";
        }
        return trimmed + suffix;
    }

    private static boolean endsWithAny(String text, String... suffixes) {
        for (String suffix : suffixes) {
            if (text.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
