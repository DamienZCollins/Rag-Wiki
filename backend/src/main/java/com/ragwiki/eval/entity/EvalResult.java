package com.ragwiki.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 评测逐条结果。失败用例的归因层与判定明细都在这里，用于定位是哪条用例退化。
 */
@Getter
@Setter
@TableName("eval_result")
public class EvalResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String caseId;

    private Boolean passed;

    /** 实际走通的通道：FASTPATH / RAG */
    private String channel;

    private String attributionLayer;

    /** 判定明细 JSON：要点命中、来源命中、工具调用、拒答判定、两路分数等 */
    private String detail;

    private String answer;

    private Integer latencyMs;

    private Integer tokenIn;

    private Integer tokenOut;

    private String error;

    private Instant createdAt;
}
