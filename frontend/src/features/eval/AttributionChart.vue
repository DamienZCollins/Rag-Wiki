<script setup lang="ts">
import { computed } from 'vue'
import type { Attribution } from '@/api/eval'
import {
  buildLayerStats,
  donutStops,
  reconcileAttribution,
  sumLayers,
  LAYER_HINT,
  type LayerKey,
} from './layers'
import LayerBadge from './LayerBadge.vue'

/**
 * 五层归因分布。
 *
 * 五层齐全，编译层不并进兜底——原型把「兜底 / 编译」合成一项，这里必须拆开。
 * 归因是「命中第一个失败层就停」，所以占比分母用**五层之和**；
 * 但真实数据里存在层之和 ≠ 失败数的情况（后端有 NONE），
 * 这时不修改数字凑平，而是在角落如实列出对账行。
 */
const props = defineProps<{
  runId: string
  attribution: Attribution | null
  failedTotal: number
  /** 失败清单里 layer 为 NONE 的条数 */
  unattributed: number
  /** 当前筛选层，null 表示未筛选 */
  selected: LayerKey | null
}>()

const emit = defineEmits<{ pick: [layer: LayerKey | null] }>()

const stats = computed(() => buildLayerStats(props.attribution, props.unattributed))
const layerSum = computed(() => sumLayers(props.attribution))
const reconcile = computed(() =>
  reconcileAttribution(props.attribution, props.failedTotal, props.unattributed),
)
const stopString = computed(() => donutStops(props.attribution))

const fiveLayers = computed(() => stats.value.filter((item) => item.key !== 'NONE'))
const unattributedStat = computed(() => stats.value.find((item) => item.key === 'NONE') ?? null)

function shareText(share: number | null): string {
  if (share === null) return '—'
  return `${(share * 100).toFixed(1)}%`
}

function toggle(layer: LayerKey) {
  emit('pick', props.selected === layer ? null : layer)
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <div class="head-left">
        <h2 class="panel-title">失败归因分布</h2>
        <span class="panel-meta">五层 · 命中第一个失败层就停</span>
      </div>
      <span class="panel-meta">
        批次 <span class="mono">{{ runId }}</span>
      </span>
    </div>

    <div class="panel-body">
      <div class="donut-wrap">
        <div
          class="donut"
          :style="{ background: `conic-gradient(${stopString})` }"
          role="img"
          :aria-label="`归因分布：${fiveLayers
            .map((item) => `${item.label} ${item.count} 条`)
            .join('，')}；五层合计 ${layerSum} 条`"
        >
          <div class="donut-center">
            <div>
              <b>{{ layerSum }}</b>
              <span>五层合计（失败 {{ failedTotal }}）</span>
            </div>
          </div>
        </div>

        <div class="legend">
          <button
            v-for="item in fiveLayers"
            :key="item.key"
            type="button"
            class="legend-row"
            :class="{ active: selected === item.key }"
            :aria-pressed="selected === item.key"
            :title="`${item.label}：${LAYER_HINT[item.key]}；点击筛选失败用例`"
            @click="toggle(item.key)"
          >
            <i :style="{ background: `var(${item.colorVar})` }" />
            <span class="legend-name">{{ item.label }}</span>
            <span class="legend-share mono">{{ shareText(item.share) }}</span>
            <span class="n mono">{{ item.count }}</span>
          </button>

          <div v-if="unattributedStat" class="legend-unattr">
            <LayerBadge layer="NONE" compact />
            <span class="unattr-text">
              未归因 {{ unattributedStat.count }} 条，不计入五层占比
            </span>
          </div>
        </div>
      </div>

      <p class="layer-note" :class="{ warn: !reconcile.consistent }">
        <template v-if="reconcile.consistent">
          归因合计 {{ reconcile.layerSum }} / 失败
          {{ reconcile.failedTotal }}，一致。占比分母为五层之和。
        </template>
        <template v-else>
          归因合计 <b>{{ reconcile.layerSum }}</b> / 失败
          <b>{{ reconcile.failedTotal }}</b>
          （差 {{ reconcile.gap }} 条，后端未归因）。占比分母为五层之和
          {{ reconcile.layerSum }}，不修改数字凑平。
        </template>
      </p>

      <p class="layer-tip">
        归因按「编译 → 召回 → 排序 → 生成 → 兜底」顺序判定，命中第一个失败层就停，
        所以每条失败只占一层。点击某一层可在下方筛选失败用例，再次点击取消筛选。
      </p>
    </div>
  </section>
</template>

<style scoped>
.head-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.donut-wrap {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

.donut {
  width: 132px;
  height: 132px;
  border-radius: 50%;
  flex: none;
  position: relative;
  border: 1px solid var(--line-soft);
}

.donut::after {
  content: '';
  position: absolute;
  inset: 24px;
  background: var(--paper-raised);
  border-radius: 50%;
}

.donut-center {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  z-index: 1;
  text-align: center;
  padding: 26px;
}

.donut-center b {
  font-family: var(--mono);
  font-size: 18px;
  display: block;
  line-height: 1.2;
  color: var(--ink);
}

.donut-center span {
  font-size: 8.5px;
  line-height: 1.35;
  color: var(--ink-faint);
  display: block;
  margin-top: 1px;
}

.legend {
  display: flex;
  flex-direction: column;
  gap: 4px;
  /* 图例列按内容宽度排，不随面板拉伸：一旦被撑宽，计数会被推到面板最右端，
     与它所属的那一行在视觉上脱节 */
  flex: 0 1 420px;
  min-width: 220px;
  max-width: 420px;
}

.legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: var(--ink-mute);
  padding: 3px 6px;
  border-radius: 4px;
  border: 1px solid transparent;
  text-align: left;
  width: 100%;
  transition:
    background 0.15s,
    border-color 0.15s;
}

.legend-row:hover {
  background: var(--paper);
}

.legend-row.active {
  background: var(--paper-sunken);
  border-color: var(--line-strong);
  color: var(--ink);
}

.legend-row i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex: none;
}

.legend-name {
  flex: none;
  min-width: 34px;
}

.legend-share {
  font-size: 9.5px;
  color: var(--ink-faint);
}

.legend-row .n {
  margin-left: auto;
  font-family: var(--mono);
  color: var(--ink-soft);
  font-size: 10px;
}

.legend-unattr {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 6px 0;
}

.unattr-text {
  font-size: 10px;
  color: var(--ink-faint);
  line-height: 1.5;
}

.layer-note {
  margin: 12px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-mute);
  padding: 7px 9px;
  border-radius: var(--r-s);
  background: var(--paper);
  border: 1px solid var(--line-soft);
}

.layer-note.warn {
  color: var(--amber);
  background: var(--amber-soft);
  border-color: var(--amber-line);
}

.layer-note b {
  font-family: var(--mono);
}

.layer-tip {
  margin: 8px 0 0;
  font-size: 9.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

@media (max-width: 760px) {
  .donut {
    width: 104px;
    height: 104px;
  }

  .donut::after {
    inset: 19px;
  }

  .donut-center b {
    font-size: 15px;
  }

  .legend {
    min-width: 150px;
  }
}
</style>
