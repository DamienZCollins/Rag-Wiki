<script setup lang="ts">
import { computed } from 'vue'
import type { RunConfig, EvalRun } from '@/api/eval'

/**
 * 配置快照。跨批次比较前先在这一块核对口径：
 * 索引源、aliasTarget、快通断开关、topK 与拒答阈值任何一项不同，指标就不可直接对比。
 */
const props = defineProps<{
  run: EvalRun
  /** 对比基准批次的配置，同名字段不一致时高亮 */
  baseline?: RunConfig | null
  baselineLabel?: string
}>()

interface Row {
  key: keyof RunConfig
  label: string
  text: string
  /** 该字段对指标口径敏感，差异要显式标出 */
  sensitive: boolean
  diff: boolean
}

function boolText(value: boolean): string {
  return value ? '开' : '关'
}

const rows = computed<Row[]>(() => {
  const config = props.run.config
  const base = props.baseline ?? null
  const make = (key: keyof RunConfig, label: string, text: string, sensitive = true): Row => ({
    key,
    label,
    text,
    sensitive,
    diff: base ? String(base[key]) !== String(config[key]) : false,
  })

  return [
    make('indexSource', '索引源 indexSource', config.indexSource),
    make('aliasTarget', '别名指向 aliasTarget', `${config.alias} → ${config.aliasTarget}`),
    make('indexVersion', '索引版本 indexVersion', config.indexVersion, false),
    make('fastPathEnabled', '快通道 fastPathEnabled', boolText(config.fastPathEnabled)),
    make('topK', '融合 topK', String(config.topK)),
    make('candidateWindow', '候选窗口 candidateWindow', String(config.candidateWindow)),
    make('rrfK', 'RRF k', String(config.rrfK), false),
    make('refuseBm25Threshold', '拒答 BM25 阈值', config.refuseBm25Threshold.toFixed(2)),
    make('refuseVectorThreshold', '拒答向量阈值', config.refuseVectorThreshold.toFixed(2)),
    make('fastPathMinConfidence', '快通道置信度阈值', config.fastPathMinConfidence.toFixed(2)),
    make('chunkMaxChars', '切分长度上限', `${config.chunkMaxChars} 字`, false),
    make('chunkOverlapChars', '切分重叠', `${config.chunkOverlapChars} 字`, false),
    make('agentMaxSteps', 'Agent 最大步数', String(config.agentMaxSteps), false),
    make('chatModel', '对话模型', config.chatModel, false),
    make('embeddingModel', 'embedding 模型', config.embeddingModel, false),
    make('embeddingDimensions', '向量维度', String(config.embeddingDimensions), false),
  ]
})

const sensitiveDiffs = computed(() => rows.value.filter((row) => row.sensitive && row.diff))
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h2 class="panel-title">配置快照</h2>
      <span class="panel-meta mono">{{ run.runId }}</span>
    </div>
    <div class="panel-body">
      <p v-if="baseline && sensitiveDiffs.length > 0" class="config-warn" role="status">
        与基线 {{ baselineLabel ?? '' }} 相比，
        {{ sensitiveDiffs.map((row) => row.label).join('、') }} 不同，指标不可直接对比。
      </p>
      <dl class="config-grid">
        <div
          v-for="row in rows"
          :key="row.key"
          class="config-cell"
          :class="{ 'is-diff': row.diff }"
        >
          <dt>{{ row.label }}</dt>
          <dd class="mono">
            {{ row.text }}
            <span v-if="row.diff" class="diff-mark">≠ 基线</span>
          </dd>
        </div>
      </dl>
    </div>
  </section>
</template>

<style scoped>
.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(196px, 1fr));
  gap: 9px 14px;
  margin: 0;
}

.config-cell {
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-s);
  background: var(--paper);
}

.config-cell.is-diff {
  border-color: var(--amber-line);
  background: var(--amber-soft);
}

.config-cell dt {
  font-size: 10px;
  color: var(--ink-faint);
  line-height: 1.5;
}

.config-cell dd {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--ink-soft);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.config-cell.is-diff dd {
  color: var(--amber);
}

.diff-mark {
  margin-left: 4px;
  font-size: 9px;
  color: var(--amber);
}

.config-warn {
  margin: 0 0 10px;
  padding: 7px 9px;
  font-size: 11px;
  line-height: 1.65;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
}

@media (max-width: 760px) {
  .config-grid {
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
    gap: 7px 9px;
  }
}
</style>
