package com.ragwiki.eval.runner;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案判定：要点命中、禁点出现、拒答识别、引用可追溯。
 *
 * <p>判定全部是文本包含匹配，不做语义相似度、不调模型当裁判：
 * 评测口径必须可复算，换个人跑同一份数据要得出同一个结论。
 *
 * <p>匹配前统一归一化（去空白、全角转半角），否则「20 元」与「20元」会判成不同结果，
 * 数字类要点全靠模型愿不愿意打空格，这个波动没有意义。
 */
public final class AnswerJudge {

    private AnswerJudge() {
    }

    /** 要点命中结果：每个要点是否命中 */
    public record PointOutcome(int total, int hit, List<Boolean> details, List<String> missed) {

        public boolean allHit() {
            return total > 0 && hit == total;
        }

        public boolean anyHit() {
            return hit > 0;
        }
    }

    /**
     * 要点判定。一个要点给多个同义表述，命中任一即算这个要点命中。
     *
     * @param answer 模型答案
     * @param points 要点列表，每个要点是它的同义表述列表
     */
    public static PointOutcome judgePoints(String answer, List<List<String>> points) {
        String normalized = normalize(answer);
        int hit = 0;
        List<Boolean> details = new ArrayList<>(points.size());
        List<String> missed = new ArrayList<>();
        for (List<String> synonyms : points) {
            boolean matched = false;
            for (String synonym : synonyms) {
                if (!synonym.isBlank() && normalized.contains(normalize(synonym))) {
                    matched = true;
                    break;
                }
            }
            details.add(matched);
            if (matched) {
                hit++;
            } else if (!synonyms.isEmpty()) {
                missed.add(synonyms.get(0));
            }
        }
        return new PointOutcome(points.size(), hit, List.copyOf(details), List.copyOf(missed));
    }

    /** 否定前缀的探测窗口，取匹配位置往前这么多字符 */
    private static final int NEGATION_WINDOW = 8;
    /** 窗口里遇到这些标点就停，否定词不跨小句生效 */
    private static final String CLAUSE_BREAK = "，。！？；,.;!?";
    /** 否定词。命中其一说明这个禁点是被否定掉的，不算违规 */
    private static final String[] NEGATIONS = {"不", "非", "无", "未", "没有", "并非", "而不是"};

    /**
     * 禁点命中：返回真正被判为违规的禁点。
     *
     * <p>带否定规避：「不是 30 元，是 20 元」里的 30 元是被否定的，
     * 直接按子串判会把正确答案判成错的。匹配位置往前看一小段，遇到否定词就跳过这一处。
     *
     * <p>这仍是字符串启发式，不做句法分析：反例是「不对，是 30 元」这种否定词管着别处的写法会被漏掉。
     * 禁点是辅助信号，主判据是 expected_answer_points，边界情形由要点判定兜底。
     */
    public static List<String> judgeForbidden(String answer, List<String> forbidden) {
        String normalized = normalize(answer);
        List<String> hits = new ArrayList<>();
        for (String point : forbidden) {
            String needle = normalize(point);
            if (needle.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int at = normalized.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                if (!negatedBefore(normalized, at)) {
                    hits.add(point);
                    break;
                }
                from = at + needle.length();
            }
        }
        return List.copyOf(hits);
    }

    private static boolean negatedBefore(String text, int matchStart) {
        int from = Math.max(0, matchStart - NEGATION_WINDOW);
        // 往前扫到小句开头为止，再在扫出来的这一段里找否定词。
        // 不能一遇到标点就直接判「无否定」：那样「20 元，不是 30 元」里已经路过的「不是」会被丢掉
        int start = matchStart;
        for (int i = matchStart - 1; i >= from; i--) {
            if (CLAUSE_BREAK.indexOf(text.charAt(i)) >= 0) {
                break;
            }
            start = i;
        }
        String window = text.substring(start, matchStart);
        for (String negation : NEGATIONS) {
            if (window.contains(negation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否可判定为「如实说查不到」。
     *
     * <p>拒答用例不要求模型一个字不差地说「拒答」：如实说明没有依据、
     * 建议转人工、说明超出服务范围，都算拒答成功。编造具体结论才算失败。
     *
     * <p>标记取自实测答案里的说法，不是凭空拟的措辞表。两条纪律：
     * <ul>
     *   <li>只收「明确拒绝」的说法。像「我是电商平台的售后客服」这种自我介绍会出现在
     *       既答了题又补一句免责声明的答案里（实测有此例），收进来会把越界作答判成拒答</li>
     *   <li>不收会出现在正常作答里的词。「超出」曾收进来，但政策答案里
     *       「超出部分要自己出」是正常结论，收它会把答对的判成拒答</li>
     * </ul>
     */
    public static boolean looksLikeRefusal(String answer) {
        String normalized = normalize(answer);
        String[] markers = {
                // 标记表只认措辞，不认上下文：判「是否拒答」要用 judgedRefusal
                // 查不到 / 没有依据。「没有关于」单收：「没有关于客服投诉电话的相关规定」
                // 里隔着修饰语，光有「没有相关」匹配不上。它是唯一一条能救回 refusal-017 的窄写法——
                // 放宽成「没有…规定」会把 policy-014、policy-041 这类
                // 「包邮规则没有明确说明首重怎么算」的正常作答一并误判，实测确认过
                "查不到", "查不了", "没有查到", "没有找到", "没找到", "没有相关",
                "找不到", "未找到", "没有依据", "没有这方面", "我这边没有", "没有关于",
                // 拒绝帮忙。「没法给」不收：它既出现在整体拒答里，也出现在
                // 「先答了正题、再补一句没法给准确原因」的正常作答里（实测 order-015、policy-041），
                // 两条真拒答都由「没有查到」兜住，收它净增误报
                "没法帮", "没办法帮", "帮不了", "帮不上", "无法回答", "无法提供", "无法为您提供",
                "无法确认", "没法做", "暂时没法", "暂时无法",
                // 超出职责范围。限定职责的说法只出现在拒答里：
                // 正常作答不会先声明「只能处理订单、退款、售后政策这类问题」。
                // 不写「属于 XX」这类照抄单条答案的说法，那是拿判据去套答案
                "不在我的", "不在我这边", "不在服务范围", "不是我的服务范围", "不是我们的服务范围",
                // 「超出了我的服务范围」要单收：多一个「了」字，上面两条都匹配不上。
                // 实测 refusal-007 正是这个写法被判成了「没拒答」
                "超出服务范围", "超出我的服务范围", "超出了服务范围", "超出了我的服务范围",
                "只能帮您处理", "只能帮你处理", "只能帮大家处理", "只能处理订单",
                // 改道。只收「已经把人转走了」的说法，不收「转人工」这个光杆词：
                // 它是这个领域的政策术语（「转人工审核」「转人工核查」出现在政策原文里），
                // 也是正常的服务提议（「我可以帮你转人工」）。
                // 实测 26 条含「转人工」的答案里只有 5 条是拒答，其余 21 条是正常作答，
                // 收它会把答对的判成拒答
                "已帮你转人工", "已经帮你转人工", "已为您转人工", "已转人工",
                "建议转人工", "建议您转人工", "建议咨询", "建议您咨询", "建议你咨询",
        };
        for (String marker : markers) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判「这一轮算不算拒答」：在标记表之上补一条「答案优先」。
     *
     * <p>标记表只看措辞，分不出这两种答案：整体拒答（「这条政策我没查到，没法给你准确答案」），
     * 和「正题答完、再对答不了的子问题如实补一句」（「订单状态是已付款待发货……至于付款后
     * 多久必须发货，知识库里没查到规定，建议转人工核实」）。后者的措辞与拒答完全一样，
     * 但要点全中，是如实作答而不是拒绝。实测 order-005 / order-014 / order-015 三条因此被判失败，
     * 它们恰恰是该用例集里最诚实的一类答案。
     *
     * <p>所以：期望要点全部命中时，不因措辞判拒答。这条只在非拒答用例上生效——
     * 拒答用例本就没有期望要点，不受影响。
     *
     * @param pointsAllHit 该用例的期望要点是否全部命中
     * @param expectRefusal 用例是否期望拒答
     */
    public static boolean judgedRefusal(String answer, boolean pointsAllHit, boolean expectRefusal) {
        if (!looksLikeRefusal(answer)) {
            return false;
        }
        return !pointsAllHit || expectRefusal;
    }

    /**
     * 答案里出现的引用编号是否都落在工具给出的片段编号范围内。
     * 编号越界说明引用了不存在的内容，是幻觉的一种。
     */
    public static boolean citationsInRange(String answer, int citationCount) {
        String normalized = normalize(answer);
        int index = 0;
        while (index < normalized.length()) {
            int open = normalized.indexOf('[', index);
            if (open < 0) {
                break;
            }
            int close = normalized.indexOf(']', open);
            if (close < 0) {
                break;
            }
            String inner = normalized.substring(open + 1, close).strip();
            if (!inner.isEmpty() && inner.chars().allMatch(Character::isDigit)) {
                int number = Integer.parseInt(inner);
                if (number < 1 || number > citationCount) {
                    return false;
                }
            }
            index = close + 1;
        }
        return true;
    }

    /** 归一化：去全部空白、全角数字与标点转半角，大小写统一 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 全角字符（！到～）转半角，数字与英文标点都能对上
            if (c >= '！' && c <= '～') {
                builder.append((char) (c - 0xFEE0));
            } else if (c == '　') {
                continue;
            } else {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }
}
