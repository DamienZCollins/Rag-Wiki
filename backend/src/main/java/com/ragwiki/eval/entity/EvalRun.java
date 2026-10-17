package com.ragwiki.eval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 评测批次。配置快照与汇总指标固化在这一行里，保证不同批次可比、可复现。
 */
@Getter
@Setter
@TableName("eval_run")
public class EvalRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    /** 中途被打断。已跑完的用例结果保留，可以接着跑剩下的 */
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String label;

    private String caseTypes;

    /** 配置快照 JSON：索引源、索引名、通道开关、模型、切分参数、拒答阈值等 */
    private String config;

    private Integer caseCount;

    private Integer passed;

    private Integer failed;

    /** 汇总指标 JSON */
    private String metrics;

    /** 归因分布 JSON */
    private String attribution;

    private Long latencyMs;

    private String status;

    private Instant startedAt;

    private Instant finishedAt;

    private Instant createdAt;
}
