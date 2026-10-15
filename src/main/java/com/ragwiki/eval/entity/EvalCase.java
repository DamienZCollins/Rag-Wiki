package com.ragwiki.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 评测用例的导入快照。JSON 列在库里是 JSON 类型，映射成字符串进出，
 * 解析与序列化由评测服务自己做——与 wiki 的 sources 列同一套做法。
 */
@Getter
@Setter
@TableName("eval_case")
public class EvalCase {

    public static final String TYPE_POLICY = "POLICY";
    public static final String TYPE_ORDER = "ORDER";
    public static final String TYPE_REFUSAL = "REFUSAL";
    public static final String TYPE_ADVERSARIAL = "ADVERSARIAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String caseId;

    private String caseType;

    private String question;

    /** JSON 数组的数组：每个要点是它的同义表述列表，命中任一即算该要点命中 */
    private String expectedAnswerPoints;

    private String expectedSources;

    private String expectedDocIds;

    private Boolean expectRefusal;

    private String expectTools;

    private String expectedToolArgs;

    private String forbiddenPoints;

    private String attributionLayer;

    private String note;

    private String sourceFile;

    private Instant createdAt;
}
