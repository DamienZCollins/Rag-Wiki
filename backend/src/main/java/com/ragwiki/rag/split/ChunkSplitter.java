package com.ragwiki.rag.split;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 Markdown 标题层级切分正文。
 *
 * <p>三条规则：
 * <ul>
 *   <li>标题路径变化就断片，不做跨小节重叠，保证「片段 → 引用」的对应关系不被搅浑；
 *       同一小节内超长时才按 maxChars 切，切出来的片段共用同一条标题路径</li>
 *   <li>表格整块保留，永不切断行，也不参与重叠</li>
 *   <li>超长的非表格块先按句末标点切，仍超长才按字符硬切，避免一句话被拦腰截断</li>
 * </ul>
 *
 * <p>纯函数，不依赖 Spring，可以直接单测。
 */
public final class ChunkSplitter {

    private static final String HEADING_PATH_SEPARATOR = " > ";
    private static final String SENTENCE_ENDINGS = "。！？；";
    private static final String TABLE_PREFIX = "|";
    private static final int MAX_HEADING_LEVEL = 6;

    private ChunkSplitter() {
    }

    public record Options(int maxChars, int overlapChars) {
    }

    /** 一个待索引的片段。headingPath 形如「运费收取与承担规则 > 运费补贴上限」 */
    public record Piece(String headingPath, String content) {
    }

    public static List<Piece> split(String markdownBody, Options options) {
        List<Block> blocks = parseBlocks(markdownBody);
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<Piece> pieces = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;
        int carried = 0;
        String currentHeadingPath = blocks.get(0).headingPath();

        for (Block block : blocks) {
            if (!block.headingPath().equals(currentHeadingPath)) {
                emit(pieces, current, currentHeadingPath);
                current.clear();
                currentLength = 0;
                carried = 0;
                currentHeadingPath = block.headingPath();
            }

            for (String part : splitOversized(block, options.maxChars())) {
                int cost = part.length() + 1;
                if (current.size() > carried && currentLength + cost > options.maxChars()) {
                    List<String> finished = List.copyOf(current);
                    emit(pieces, finished, currentHeadingPath);
                    current.clear();
                    current.addAll(tailWithin(finished, options.overlapChars()));
                    carried = current.size();
                    currentLength = lengthOf(current);
                }
                current.add(part);
                currentLength += cost;
            }
        }
        emit(pieces, current, currentHeadingPath);
        return pieces;
    }

    private static void emit(List<Piece> pieces, List<String> blocks, String headingPath) {
        if (blocks.isEmpty()) {
            return;
        }
        String text = String.join("\n", blocks).trim();
        if (!text.isEmpty()) {
            pieces.add(new Piece(headingPath, text));
        }
    }

    /** 取尾部若干块，总长不超过 limit，用于片段之间的重叠 */
    private static List<String> tailWithin(List<String> blocks, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> tail = new ArrayList<>();
        int total = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String block = blocks.get(i);
            if (block.startsWith(TABLE_PREFIX) || total + block.length() > limit) {
                break;
            }
            tail.add(0, block);
            total += block.length();
        }
        return tail;
    }

    private static int lengthOf(List<String> blocks) {
        int total = 0;
        for (String block : blocks) {
            total += block.length() + 1;
        }
        return total;
    }

    /** 超长的非表格块先按句末标点切，再对仍然超长的单句按字符硬切；表格原样返回 */
    private static List<String> splitOversized(Block block, int maxChars) {
        String text = block.text();
        if (block.table()) {
            return List.of(text);
        }

        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (SENTENCE_ENDINGS.indexOf(c) >= 0 || c == '\n') {
                sentences.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            sentences.add(text.substring(start));
        }

        List<String> parts = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.length() <= maxChars) {
                parts.add(sentence);
                continue;
            }
            for (int i = 0; i < sentence.length(); i += maxChars) {
                parts.add(sentence.substring(i, Math.min(sentence.length(), i + maxChars)));
            }
        }
        return parts;
    }

    /** 把正文切成块，块带上当时所处的标题路径 */
    private static List<Block> parseBlocks(String markdownBody) {
        List<Block> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        boolean bufferIsTable = false;

        for (String rawLine : markdownBody.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            boolean blank = line.isBlank();
            boolean heading = isHeading(line);
            boolean table = !blank && line.stripLeading().startsWith(TABLE_PREFIX);

            if ((blank || heading || table != bufferIsTable) && buffer.length() > 0) {
                blocks.add(new Block(String.join(HEADING_PATH_SEPARATOR, headings), buffer.toString(), bufferIsTable));
                buffer.setLength(0);
            }

            if (heading) {
                updateHeadings(headings, line);
                continue;
            }
            if (blank) {
                continue;
            }
            bufferIsTable = table;
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(line);
        }
        if (buffer.length() > 0) {
            blocks.add(new Block(String.join(HEADING_PATH_SEPARATOR, headings), buffer.toString(), bufferIsTable));
        }
        return blocks;
    }

    private static void updateHeadings(List<String> headings, String line) {
        int level = headingLevel(line);
        while (headings.size() >= level) {
            headings.remove(headings.size() - 1);
        }
        headings.add(line.substring(level + 1).trim());
    }

    private static boolean isHeading(String line) {
        return headingLevel(line) > 0;
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        boolean valid = level > 0 && level <= MAX_HEADING_LEVEL
                && level < line.length() && line.charAt(level) == ' ';
        return valid ? level : 0;
    }

    private record Block(String headingPath, String text, boolean table) {
    }
}
