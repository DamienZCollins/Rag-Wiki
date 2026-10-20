package com.ragwiki.wiki.fastpath;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 快通道关键词的词表。
 *
 * <p>5.2 要求派生过程不做模型调用，而中文切词没有模型就得有词典，
 * 所以这里维护一份售后领域的术语表：从条目标题里挑出命中的术语当关键词。
 *
 * <p>为什么是术语表而不是切词算法：关键词要的是「用户真会打出来的词」，
 * 术语表满足这一点且完全确定；换成真正的分词器时只需要替换这一个类。
 */
@Component
public class FastPathLexicon {

    /** 按长度降序排列，长术语优先命中，避免「运费险」被「运费」抢走 */
    private static final List<String> TERMS = List.of(
            "七天无理由", "无理由退货", "运费补贴", "运费承担", "运费险", "运费券", "包邮门槛", "包邮规则",
            "质量问题", "质量判定", "退换货", "退货", "换货", "退款", "退运",
            "发票换开", "发票", "开票", "冲红", "价保",
            "会员等级", "会员券", "会员", "运费", "生鲜", "大件", "物流", "快递", "签收", "丢件", "虚假签收",
            "报损", "举证", "判定", "时效", "时限", "上限", "额度", "门槛", "转人工", "工单",
            "安装损坏", "送装", "验货", "免责", "例外", "争议", "申诉", "驳回", "受理"
    );

    private static final int MAX_KEYWORDS = 3;

    /**
     * 从标题里挑出术语当关键词。
     * 一个都没命中时退化为标题本身——宁可匹配得严，也不要让条目完全无法命中。
     */
    public List<String> extract(String title) {
        String normalized = FastPathText.normalize(title);
        Set<String> matched = new LinkedHashSet<>();
        for (String term : TERMS) {
            if (matched.size() >= MAX_KEYWORDS) {
                break;
            }
            if (normalized.contains(term)) {
                matched.add(term);
            }
        }
        if (matched.isEmpty() && !normalized.isEmpty()) {
            return List.of(title.strip());
        }
        return List.copyOf(matched);
    }

    /** 从用户问题里挑关键词，供流量沉淀使用 */
    public List<String> extractFromQuestion(String question) {
        List<String> terms = extract(question);
        if (terms.size() == 1 && terms.get(0).equals(question.strip())) {
            // 没命中任何术语，用问题本身当关键词，至少能靠精确匹配生效
            return List.of(question.strip());
        }
        return terms;
    }

    /** 供统计与排查用 */
    public List<String> terms() {
        return new ArrayList<>(TERMS);
    }
}
