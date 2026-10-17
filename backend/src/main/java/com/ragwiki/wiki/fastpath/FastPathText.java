package com.ragwiki.wiki.fastpath;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 快通道的文本处理。问题规范化后用于精确匹配与条目 id 生成，
 * 所以「退货运费谁出」「退货 运费谁出？」「退货运费谁出?」被当作同一个问题。
 */
public final class FastPathText {

    private static final String PUNCTUATION = "[\\s，,。.！!？?、；;：:\"'“”‘’（）()【】\\[\\]《》<>~～]+";
    private static final String ENTRY_ID_PREFIX = "fp-";
    private static final int ENTRY_ID_HASH_LENGTH = 16;

    private FastPathText() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll(PUNCTUATION, "").toLowerCase();
    }

    /** 条目 id 由规范化问题决定，重复派生写的是同一个 id，所以是幂等的 */
    public static String entryId(String question) {
        return ENTRY_ID_PREFIX + sha256(normalize(question)).substring(0, ENTRY_ID_HASH_LENGTH);
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
