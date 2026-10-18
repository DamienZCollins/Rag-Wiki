<script setup lang="ts">
import { computed } from 'vue'
import type { CaseDetail } from '@/api/eval'
import { formatLatency } from './format'
import LayerBadge from './LayerBadge.vue'
import type { LayerKey } from './layers'

/**
 * 单条失败用例的归因链路。
 *
 * 只呈现接口真给了的字段；缺失的字段写「接口未返回」，不猜也不补零。
 * 检索侧数据用来判断「是召回的问题还是排序的问题」：
 * expectedBestRank 为 0 表示期望来源压根没进候选窗口，> 1 表示进了但没排到第一。
 */
const props = defineProps<{
  layer: LayerKey
  detail: CaseDetail
  error: string | null
  channel: string | null
  latencyMs: number
  tokenIn: number | null
  tokenOut: number | null
}>()

interface CheckItem {
  key: string
  label: string
  text: string
  state: 'ok' | 'fail' | 'na'
  note?: string
}

function listText(items: string[] | undefined): string {
  if (!items || items.length === 0) return '无'
  return items.join('、')
}

function boolState(value: boolean | undefined, failIsBad = true): 'ok' | 'fail' | 'na' {
  if (value === undefined || value === null) return 'na'
  if (failIsBad) return value ? 'ok' : 'fail'
  return value ? 'fail' : 'ok'
}

/** 要点命中：pointsHit / pointsTotal，漏了哪些点也要列出 */
const pointCheck = computed<CheckItem>(() => {
  const total = props.detail.pointsTotal
  const hit = props.detail.pointsHit
  const missed = props.detail.pointsMissed ?? []
  if (total === undefined || hit === undefined) {
    return { key: 'points', label: '答案要点', text: '接口未返回', state: 'na' }
  }
  if (total === 0) {
    return {
      key: 'points',
      label: '答案要点',
      text: '该用例无期望要点，不做要点判定',
      state: 'na',
      note: '口径：要点列表为空时只判其余几项',
    }
  }
  const ok = missed.length === 0 && hit >= total
  return {
    key: 'points',
    label: '答案要点',
    text: `${hit}/${total} 命中${missed.length > 0 ? `，漏：${listText(missed)}` : ''}`,
    state: ok ? 'ok' : 'fail',
  }
})

const checks = computed<CheckItem[]>(() => {
  const d = props.detail
  const forbidden = d.forbiddenHits ?? []
  return [
    pointCheck.value,
    {
      key: 'forbidden',
      label: '禁点命中',
      text:
        forbidden.length === 0 ? '无命中' : `命中 ${forbidden.length} 项：${listText(forbidden)}`,
      state: forbidden.length === 0 ? 'ok' : 'fail',
      note: '命中即算幻觉（用了已失效的旧口径）',
    },
    {
      key: 'tool',
      label: '期望工具',
      text:
        d.toolHit === undefined
          ? '接口未返回'
          : d.toolHit
            ? '已调用'
            : `未调用${(d.toolArgMisses ?? []).length > 0 ? `，参数未命中：${listText(d.toolArgMisses)}` : ''}`,
      state: boolState(d.toolHit),
      note: '快通道命中时不判工具（按设计不调模型）',
    },
    {
      key: 'citation',
      label: '引用越界',
      text: d.citationOk === undefined ? '接口未返回' : d.citationOk ? '无越界' : '存在越界引用',
      state: boolState(d.citationOk),
      note: '答案里出现 [n] 但超出工具给出的片段数即为越界',
    },
    {
      key: 'refused',
      label: '实际拒答',
      text:
        d.actualRefused === undefined
          ? '接口未返回'
          : d.actualRefused
            ? '已拒答'
            : '未拒答（正常作答）',
      state: 'na',
      note: '需与用例的 expect_refusal 比对才能判好坏，单看该字段不下结论',
    },
    {
      key: 'missing',
      label: '期望来源缺失',
      text:
        d.sourceMissing === undefined
          ? '接口未返回'
          : d.sourceMissing
            ? `缺失：${listText(d.missingSources)}`
            : '未缺失',
      state: d.sourceMissing === undefined ? 'na' : d.sourceMissing ? 'fail' : 'ok',
      note: '来源在 Wiki 库里就不存在 → 编译层问题',
    },
  ]
})

/** 检索侧数据。没有期望来源的用例（订单 / 拒答）没有探针结果 */
const retrieval = computed(() => {
  const d = props.detail
  if (d.hasExpectedSource === false) {
    return { available: false, reason: '该用例不带期望来源（订单 / 拒答类），不参与检索判定' }
  }
  const rank = d.expectedBestRank
  let rankText = '接口未返回'
  let rankState: 'ok' | 'fail' | 'na' = 'na'
  if (typeof rank === 'number') {
    if (rank === 0) {
      rankText = '未召回（0）'
      rankState = 'fail'
    } else if (rank === 1) {
      rankText = '第 1 位'
      rankState = 'ok'
    } else {
      rankText = `第 ${rank} 位`
      rankState = 'fail'
    }
  }
  return {
    available: true,
    reason: '',
    rankText,
    rankState,
    bestRank: rank,
    probeRefused: d.probeRefused,
    probeTopBm25: d.probeTopBm25,
    probeTopVector: d.probeTopVector,
    chunks: d.probeChunks ?? [],
  }
})

/** 探针片段列表单独取出来，模板里不再对可能为 undefined 的字段做索引 */
const probeChunks = computed(() => retrieval.value.chunks ?? [])
</script>

<template>
  <div class="trace">
    <div class="trace-head">
      <LayerBadge :layer="layer" />
      <span class="trace-meta mono">
        {{ channel ?? '通道未知' }} · {{ formatLatency(latencyMs) }} · token {{ tokenIn ?? '—' }} /
        {{ tokenOut ?? '—' }}
      </span>
    </div>

    <p v-if="error" class="chain-error" role="alert">链路报错：{{ error }}</p>

    <div class="trace-cols">
      <section class="trace-block">
        <h3 class="trace-title">判定明细</h3>
        <dl class="check-list">
          <div v-for="item in checks" :key="item.key" class="check-row" :class="item.state">
            <dt>
              <span class="dot" aria-hidden="true" />
              {{ item.label }}
              <span class="state-text">{{
                item.state === 'ok' ? '通过' : item.state === 'fail' ? '未通过' : '不参与判定'
              }}</span>
            </dt>
            <dd>
              {{ item.text }}
              <span v-if="item.note" class="check-note">{{ item.note }}</span>
            </dd>
          </div>
        </dl>
      </section>

      <section class="trace-block">
        <h3 class="trace-title">检索侧数据</h3>
        <template v-if="retrieval.available">
          <dl class="retrieval-grid">
            <div>
              <dt>期望来源最优名次 expectedBestRank</dt>
              <dd class="mono" :class="retrieval.rankState">{{ retrieval.rankText }}</dd>
            </div>
            <div>
              <dt>探针是否被拒答 probeRefused</dt>
              <dd class="mono">
                {{
                  retrieval.probeRefused === undefined
                    ? '接口未返回'
                    : retrieval.probeRefused
                      ? '是'
                      : '否'
                }}
              </dd>
            </div>
            <div>
              <dt>探针 BM25 最高分</dt>
              <dd class="mono">
                {{
                  retrieval.probeTopBm25 === undefined
                    ? '接口未返回'
                    : retrieval.probeTopBm25.toFixed(6)
                }}
              </dd>
            </div>
            <div>
              <dt>探针向量最高分</dt>
              <dd class="mono">
                {{
                  retrieval.probeTopVector === undefined
                    ? '接口未返回'
                    : retrieval.probeTopVector.toFixed(6)
                }}
              </dd>
            </div>
          </dl>
          <p class="retrieval-hint">
            名次 0 表示期望来源没进候选窗口（召回层）；进了窗口但超出 topK 则是排序层。
          </p>
          <ol v-if="probeChunks.length > 0" class="chunks">
            <li v-for="(chunk, index) in probeChunks" :key="chunk.chunkId">
              <span class="chunk-rank mono">{{ index + 1 }}</span>
              <span class="chunk-title">{{ chunk.title }}</span>
              <span class="chunk-id mono">{{ chunk.chunkId }}</span>
            </li>
          </ol>
        </template>
        <p v-else class="retrieval-empty">{{ retrieval.reason }}</p>
      </section>
    </div>

    <p v-if="detail.finishReason" class="trace-foot">
      finishReason：<span class="mono">{{ detail.finishReason }}</span>
      <template v-if="detail.caseType">
        · 用例类型：<span class="mono">{{ detail.caseType }}</span>
      </template>
    </p>
  </div>
</template>

<style scoped>
.trace {
  padding: 10px 12px 12px;
  background: var(--paper);
  border-top: 1px solid var(--line-soft);
}

.trace-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.trace-meta {
  font-size: 10px;
  color: var(--ink-faint);
}

.chain-error {
  margin: 0 0 8px;
  padding: 6px 9px;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--oxblood);
  background: var(--oxblood-soft);
  border: 1px solid var(--oxblood-line);
  border-radius: var(--r-s);
}

.trace-cols {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 0.95fr);
  gap: 14px;
}

.trace-block {
  min-width: 0;
}

.trace-title {
  margin: 0 0 6px;
  font-size: 10px;
  font-weight: 600;
  color: var(--ink-faint);
  letter-spacing: 0.04em;
}

.check-list {
  margin: 0;
}

.check-row {
  padding: 4px 0;
  border-bottom: 1px dashed var(--line);
}

.check-row:last-child {
  border-bottom: 0;
}

.check-row dt {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 10.5px;
  color: var(--ink-mute);
}

.check-row .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  flex: none;
  background: var(--line-strong);
}

.check-row.ok .dot {
  background: var(--green);
}

.check-row.fail .dot {
  background: var(--oxblood);
}

.state-text {
  font-size: 9px;
  padding: 0 4px;
  border-radius: 3px;
  border: 1px solid var(--line);
  color: var(--ink-faint);
  line-height: 13px;
}

.check-row.ok .state-text {
  color: var(--green);
  border-color: var(--green-line);
  background: var(--green-soft);
}

.check-row.fail .state-text {
  color: var(--oxblood);
  border-color: var(--oxblood-line);
  background: var(--oxblood-soft);
}

.check-row dd {
  margin: 1px 0 0 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}

.check-row.fail dd {
  color: var(--oxblood);
}

.check-note {
  display: block;
  font-size: 9px;
  color: var(--ink-faint);
  line-height: 1.5;
}

.retrieval-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 3px;
  margin: 0;
}

.retrieval-grid dt {
  font-size: 9.5px;
  color: var(--ink-faint);
}

.retrieval-grid dd {
  margin: 0 0 3px;
  font-size: 11px;
  color: var(--ink-soft);
}

.retrieval-grid dd.ok {
  color: var(--green);
}

.retrieval-grid dd.fail {
  color: var(--amber);
}

.retrieval-hint {
  margin: 6px 0 0;
  font-size: 9px;
  line-height: 1.6;
  color: var(--ink-faint);
}

.chunks {
  list-style: none;
  margin: 7px 0 0;
  padding: 6px 0 0;
  border-top: 1px dashed var(--line);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.chunks li {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 10px;
  color: var(--ink-mute);
  min-width: 0;
}

.chunk-rank {
  font-size: 9px;
  color: var(--ink-faint);
  flex: none;
  min-width: 12px;
}

.chunk-title {
  color: var(--ink-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chunk-id {
  margin-left: auto;
  font-size: 9px;
  color: var(--ink-faint);
  flex: none;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.retrieval-empty {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

.trace-foot {
  margin: 9px 0 0;
  padding-top: 7px;
  border-top: 1px solid var(--line-soft);
  font-size: 9.5px;
  color: var(--ink-faint);
}

@media (max-width: 1080px) {
  .trace-cols {
    grid-template-columns: 1fr;
    gap: 10px;
  }
}
</style>
