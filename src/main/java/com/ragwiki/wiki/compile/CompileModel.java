package com.ragwiki.wiki.compile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragwiki.common.exception.BusinessException;
import com.ragwiki.common.model.ChatClient;
import com.ragwiki.common.model.dto.ChatMessage;
import com.ragwiki.common.model.dto.ChatCompletion;
import com.ragwiki.common.result.ResultCode;
import com.ragwiki.wiki.schema.SourceDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Wiki 离线编译的模型调用。走 model.compile 那一组配置，与在线问答的模型分开，
 * 可以换更贵的模型、可以单独限额。
 */
@Component
public class CompileModel {

    private static final Logger log = LoggerFactory.getLogger(CompileModel.class);
    private static final String JSON_OBJECT_START = "{";
    private static final String JSON_OBJECT_END = "}";
    private static final String FENCE = "```";
    /** 一次送去判定的条目对数量。送太多时模型容易返回空内容 */
    private static final int CONFLICT_BATCH_SIZE = 5;

    private static final String EXTRACT_SYSTEM = """
            你在把电商售后政策文档编译成结构化的 Wiki 条目。
            一个条目只讲一个概念。不要把整篇文档塞进一个条目，也不要为了凑数把一句话拆成好几条。
            """;

    private static final String CONFLICT_SYSTEM = """
            你在检查一个售后政策知识库里条目之间是否存在互相矛盾的结论。

            算矛盾：同一个概念，两条条目给出了不同的数值、不同的适用范围或相反的结论，
            而两边都没有说明自己只是对方的一个特例。
            例：一条说「质量问题退货运费统一补贴 30 元」，另一条说「普通会员补 20 元、金卡会员补 30 元」——
            这是矛盾，因为前者的口径覆盖了后者。

            不算矛盾：表述详略不同、同义改写、两条各自明确限定了互不重叠的场景、
            以及明确写了「例外」「不适用」的从属关系。
            宁可少报也不要误报。
            """;

    private final ChatClient compileClient;
    private final ObjectMapper objectMapper;

    public CompileModel(@Qualifier("compileChatClient") ChatClient compileClient,
                        ObjectMapper objectMapper) {
        this.compileClient = compileClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从一篇源文档抽取候选条目，并让模型判断该归并到哪条已有条目。
     * 已有条目带上摘要与适用条件，模型归并时才知道哪些要点不能丢。
     */
    public List<ExtractedEntry> extract(SourceDoc doc, String body, List<ExistingEntry> existingEntries) {
        String user = """
                已有条目（标题 / 摘要 / 适用条件）：
                %s

                待编译的政策文档《%s》（%s，生效日期 %s）：

                %s
                """.formatted(renderExisting(existingEntries), doc.getTitle(),
                doc.getVersion(), doc.getEffectiveAt(), body);

        return askForEntries(user);
    }

    private List<ExtractedEntry> askForEntries(String user) {
        String system = EXTRACT_SYSTEM + "\n" + """
                输出格式（只输出 JSON 对象本身，不要 markdown 代码块，不要额外说明）：
                {
                  "entries": [
                    {
                      "title": "条目标题，不超过 20 字的名词短语。绝对不要带版本号、年份、日期",
                      "mergeInto": "要归并到的已有条目标题，新建时填空字符串",
                      "summary": "一到三句话说明这条规则是什么，合并时要把已有条目的要点一并涵盖",
                      "conditions": ["适用条件，一条一句"],
                      "exceptions": ["例外情形，一条一句，没有就空数组"],
                      "related": ["相关的其它条目标题"]
                    }
                  ]
                }

                归并规则：
                - 标题是概念的主键。同一个概念在全库只能有一个标题，不管它出现在几篇文档里
                - 如果某个概念与上面已有条目里的某一条讲的是同一件事，即使措辞不完全一样、数值有变化，
                  也必须归并：mergeInto 一字不差地填已有条目的标题，title 也填同一个字符串
                - 归并时把本文档的新信息合并进 summary 与 conditions，不要丢掉已有条目的要点；
                  数值有变化时在 summary 里说明变化前后，但不要把版本号写进标题
                - 标题里禁止出现版本号、年份、日期、「2024 版」这类字样
                - 确实是新概念才新建，标题自己起，mergeInto 填空字符串
                - related 里只写你判断确实相关的条目标题，可以是已有条目，也可以是本篇新抽出来的条目
                """;

        Completion completion = complete(system, user);
        if (completion.content() == null || completion.content().isBlank()) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "编译模型返回了空内容，finish_reason=" + completion.finishReason());
        }
        ExtractedEntries parsed = readJson(completion, ExtractedEntries.class);
        if (parsed == null || parsed.entries() == null) {
            throw new BusinessException(ResultCode.MODEL_RESPONSE_INVALID,
                    "编译模型没有返回 entries 数组：" + abbreviate(completion.content()));
        }
        log.debug("抽取到 {} 个候选条目", parsed.entries().size());
        return parsed.entries();
    }

    /** 判断候选条目对之间是否有结论冲突。分批送，一批太多时模型容易直接返回空 */
    public List<Conflict> judgeConflicts(List<ConflictCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += CONFLICT_BATCH_SIZE) {
            List<ConflictCandidate> batch = candidates.subList(
                    start, Math.min(candidates.size(), start + CONFLICT_BATCH_SIZE));
            for (Conflict conflict : judgeBatch(batch)) {
                conflicts.add(new Conflict(conflict.pair() + start, conflict.reason()));
            }
        }
        return conflicts;
    }

    private List<Conflict> judgeBatch(List<ConflictCandidate> candidates) {
        StringBuilder builder = new StringBuilder("以下是知识库里几对相关条目的摘要与适用条件：\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            ConflictCandidate candidate = candidates.get(i);
            builder.append("【第 ").append(i + 1).append(" 对】\n");
            builder.append("A. ").append(candidate.leftTitle()).append('\n')
                    .append(candidate.leftSummary()).append('\n');
            builder.append("B. ").append(candidate.rightTitle()).append('\n')
                    .append(candidate.rightSummary()).append('\n');
            builder.append('\n');
        }

        String system = CONFLICT_SYSTEM + """
                输出格式（只输出 JSON 对象本身，不要 markdown 代码块）：
                {
                  "conflicts": [
                    {
                      "pair": 第几对，从 1 开始,
                      "reason": "一句话说明冲突在哪，把两边的结论都点出来"
                    }
                  ]
                }
                没有冲突就返回 {"conflicts": []}。宁可少报也不要误报。
                """;

        Completion completion = complete(system, builder.toString());
        if (completion.content() == null || completion.content().isBlank()) {
            log.warn("矛盾检测返回空内容，finish_reason={}，这一批按无冲突处理", completion.finishReason());
            return List.of();
        }
        ConflictReport report = readJson(completion, ConflictReport.class);
        if (report == null || report.conflicts() == null) {
            return List.of();
        }
        return report.conflicts();
    }

    private Completion complete(String system, String user) {
        List<ChatMessage> messages = List.of(ChatMessage.system(system), ChatMessage.user(user));
        List<String> deltas = new ArrayList<>();
        ChatCompletion completion = compileClient.stream(messages, List.of(), deltas::add);

        String content = completion.content() != null ? completion.content() : String.join("", deltas);
        return new Completion(content, completion.finishReason());
    }

    private <T> T readJson(Completion completion, Class<T> type) {
        String raw = completion.content();
        String json = stripToJsonObject(raw);
        if (json == null) {
            log.warn("编译模型输出里找不到 JSON 对象，长度 {}，finish_reason={}：{}",
                    raw.length(), completion.finishReason(), abbreviate(raw));
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // 被 max-tokens 截断是最常见的失败原因，把长度与终止原因打出来，方便判断该不该调大上限
            log.warn("编译模型输出不是合法 JSON（{}）：长度 {}，finish_reason={}，解析错误 {}：{}",
                    type.getSimpleName(), raw.length(), completion.finishReason(),
                    e.getMessage(), abbreviate(raw));
            return null;
        }
    }

    /** 模型有时会套一层 markdown 代码块，剥掉它，只取最外层的大括号 */
    static String stripToJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.startsWith(FENCE)) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf(FENCE);
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int start = text.indexOf(JSON_OBJECT_START);
        int end = text.lastIndexOf(JSON_OBJECT_END);
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private String renderExisting(List<ExistingEntry> entries) {
        if (entries.isEmpty()) {
            return "（知识库当前没有条目，全部新建）";
        }
        StringBuilder builder = new StringBuilder();
        for (ExistingEntry entry : entries) {
            builder.append("- 标题：").append(entry.title());
            if (entry.version() != null) {
                builder.append("（").append(entry.version()).append("）");
            }
            builder.append('\n');
            if (entry.summary() != null && !entry.summary().isBlank()) {
                builder.append("  摘要：").append(oneLine(entry.summary())).append('\n');
            }
            if (!entry.conditions().isEmpty()) {
                builder.append("  适用条件：").append(oneLine(String.join("；", entry.conditions()))).append('\n');
            }
        }
        return builder.toString();
    }

    private static String oneLine(String text) {
        String flat = text.replace('\n', ' ').strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractedEntries(List<ExtractedEntry> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConflictReport(List<Conflict> conflicts) {
    }

    /** 已有条目的快照，供模型判断归并目标 */
    public record ExistingEntry(String title, String version, String summary, List<String> conditions) {
    }

    /** 送去判定的候选条目对 */
    public record ConflictCandidate(String leftTitle, String leftSummary,
                                    String rightTitle, String rightSummary) {
    }

    public record Conflict(int pair, String reason) {
    }

    private record Completion(String content, String finishReason) {
    }
}
