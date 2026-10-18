<script setup lang="ts">
import { computed } from 'vue'
import type { CompareGroupData, CompareMetric } from './compare'
import AppIcon from '@/components/AppIcon.vue'

/**
 * 对比实验图。
 *
 * 两组分开、量纲分开：比率一根轴，token 一根轴，绝不把不同口径画在同一根柱子上。
 * 两臂数值相同时**不画两根等高柱**——等高柱会让人误以为「两条独立测量恰好相等」，
 * 实际是同一个数值，所以改成单柱 + 「两臂相同 · 无差异」文字。
 */
const props = defineProps<{
  groups: CompareGroupData[]
}>()

const emit = defineEmits<{ jump: [runId: string] }>()

/** 每组的柱色：第一臂用 line-strong（原始文档 / 双通道），第二臂用 teal（Wiki / 纯 RAG） */
const ARM_COLORS = ['var(--line-strong)', 'var(--teal)'] as const

function barStyle(height: number, armIndex: number) {
  return {
    height: `${Math.max(height, 0.6)}%`,
    background: ARM_COLORS[armIndex],
  }
}

function sameText(metric: CompareMetric): string {
  return `两臂相同 ${metric.texts[0]} · 无差异`
}

function ariaFor(group: CompareGroupData, metric: CompareMetric): string {
  const [left, right] = group.arms
  if (metric.same) {
    return `${metric.label}：${left.shortLabel}与${right.shortLabel}均为 ${metric.texts[0]}，无差异`
  }
  return `${metric.label}：${left.shortLabel} ${metric.texts[0]}，${right.shortLabel} ${metric.texts[1]}，差值 ${metric.deltaText}`
}

/** 事实性小结：只陈述哪一臂更高、差多少、按该指标口径谁更优，不做因果推断 */
const summaryRows = computed(() =>
  props.groups.map((group) => {
    const [left, right] = group.arms
    const lines = group.blocks.flatMap((block) =>
      block.metrics.map((metric) => {
        const key = `${group.kind}-${metric.key}`
        if (metric.same) {
          return {
            key,
            label: metric.label,
            verdict: `两臂相同 ${metric.texts[0]}，无差异，图上不画两根等高柱`,
            same: true,
          }
        }
        const leftHigher = metric.delta > 0
        const higherArm = leftHigher ? left : right
        const lowerArm = leftHigher ? right : left
        const betterArm = metric.higherBetter ? higherArm : lowerArm
        const diff =
          metric.key.startsWith('ragToken') && !metric.key.endsWith('Rate')
            ? Math.abs(metric.delta).toLocaleString('en-US')
            : Math.abs(metric.delta).toFixed(4)
        return {
          key,
          label: metric.label,
          verdict: `${higherArm.shortLabel} 高于 ${lowerArm.shortLabel} ${diff}；该项${
            metric.higherBetter ? '越高越好' : '越低越好'
          }，${betterArm.shortLabel} 更优`,
          same: false,
        }
      }),
    )
    return { group, lines }
  }),
)
</script>

<template>
  <div class="compare-stack">
    <section v-for="{ group, lines } in summaryRows" :key="group.kind" class="panel">
      <div class="panel-head">
        <div class="head-left">
          <h2 class="panel-title">{{ group.title }}</h2>
          <span class="panel-meta">{{ group.meta }}</span>
        </div>
        <span class="panel-meta">
          当前批次
          <span class="mono">{{ group.arms[0].runId }}</span>
          / 对照
          <span class="mono">{{ group.arms[1].runId }}</span>
        </span>
      </div>

      <div class="panel-body">
        <p v-for="(warning, index) in group.warnings" :key="index" class="arm-warn" role="status">
          <AppIcon name="warning" :size="13" />
          {{ warning }}
        </p>

        <div class="chart-legend">
          <button
            v-for="(arm, index) in group.arms"
            :key="arm.runId"
            type="button"
            class="legend-item"
            :title="`打开批次 ${arm.runId}`"
            @click="emit('jump', arm.runId)"
          >
            <i :style="{ background: ARM_COLORS[index] }" />
            <span>{{ arm.shortLabel }}</span>
            <span class="legend-run mono">{{ arm.runId }}</span>
          </button>
          <span class="legend-note">{{ group.arms[0].label }} / {{ group.arms[1].label }}</span>
        </div>

        <div v-for="block in group.blocks" :key="block.unit" class="block">
          <div class="block-unit">
            <span>量纲：{{ block.unit }}</span>
            <span class="axis-note mono">
              轴上限 {{ block.axisMaxText }} · {{ block.axisNote }}
            </span>
          </div>
          <div class="bars" role="group" :aria-label="`${group.title}柱状对比`">
            <div v-for="metric in block.metrics" :key="metric.key" class="bar-col">
              <div v-if="metric.same" class="bar-pair" :aria-label="ariaFor(group, metric)">
                <span
                  class="bar same"
                  :style="{ height: `${Math.max(metric.heights[0], 0.6)}%` }"
                  :title="sameText(metric)"
                />
              </div>

              <div v-else class="bar-pair" :aria-label="ariaFor(group, metric)">
                <span
                  class="bar"
                  :style="barStyle(metric.heights[0], 0)"
                  :title="`${group.arms[0].shortLabel} ${metric.texts[0]}`"
                />
                <span
                  class="bar"
                  :style="barStyle(metric.heights[1], 1)"
                  :title="`${group.arms[1].shortLabel} ${metric.texts[1]}`"
                />
              </div>

              <span class="pair-values mono">
                <template v-if="metric.same">
                  <span class="v-same">{{ metric.texts[0] }}</span>
                </template>
                <template v-else>
                  <span class="v0">{{ metric.texts[0] }}</span>
                  <span class="v1">{{ metric.texts[1] }}</span>
                </template>
              </span>

              <span class="x">{{ metric.label }}</span>
              <span v-if="metric.same" class="same-flag">两臂相同 · 无差异</span>
              <span v-else class="delta">{{ metric.deltaText }}</span>
            </div>
          </div>
        </div>

        <ul class="facts">
          <li v-for="line in lines" :key="line.key" :class="{ same: line.same }">
            <b>{{ line.label }}</b>
            <span>{{ line.verdict }}</span>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.compare-stack {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.head-left {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.arm-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0 0 10px;
  padding: 6px 9px;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--amber);
  background: var(--amber-soft);
  border: 1px solid var(--amber-line);
  border-radius: var(--r-s);
}

.chart-legend {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: var(--ink-mute);
  padding: 1px 4px;
  border-radius: 4px;
}

.legend-item:hover {
  background: var(--paper);
  color: var(--ink);
}

.legend-item i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  display: block;
  flex: none;
}

.legend-run {
  font-size: 9px;
  color: var(--ink-faint);
}

.legend-note {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
}

.block + .block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line-soft);
}

.block-unit {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 9.5px;
  color: var(--ink-faint);
  margin-bottom: 5px;
}

.axis-note {
  font-size: 9px;
  color: var(--ink-faint);
}

.bars {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  min-height: 190px;
  padding: 4px 0 0;
  border-bottom: 1px solid var(--line-soft);
}

.bar-col {
  /* 单个指标时不要横跨整个宽度：柱子会宽得像色块 */
  flex: 1 1 0;
  max-width: 220px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  height: 190px;
}

.bar-pair {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  flex: 1;
  width: 100%;
  justify-content: center;
  min-height: 0;
}

.bar {
  width: 26px;
  border-radius: 3px 3px 0 0;
  min-height: 3px;
  position: relative;
  display: block;
}

/* 数值放在柱底下方对齐排列，不再浮在柱顶：柱高接近时浮标会互相压住 */
.pair-values {
  display: flex;
  justify-content: center;
  gap: 6px;
  font-size: 9px;
  line-height: 1.3;
  color: var(--ink-soft);
  white-space: nowrap;
}

.pair-values span {
  min-width: 0;
}

.same-col {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 3px;
  flex: 1;
  height: 100%;
  min-height: 0;
}

.same-bar-wrap {
  flex: 1;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
  min-height: 0;
}

.bar.same {
  width: 46px;
  background: repeating-linear-gradient(
    135deg,
    var(--line-strong) 0 5px,
    var(--paper-deep) 5px 10px
  );
  border: 1px solid var(--line-strong);
  border-bottom: 0;
}

.same-value {
  font-size: 9.5px;
  color: var(--ink-soft);
}

.v-same {
  color: var(--ink-soft);
}

.same-flag {
  font-size: 9px;
  color: var(--teal);
  background: var(--teal-soft);
  border: 1px solid var(--teal-line);
  border-radius: 3px;
  padding: 0 5px;
  line-height: 14px;
  white-space: nowrap;
}

.bar-col .x {
  font-size: 9.5px;
  color: var(--ink-faint);
  font-family: var(--mono);
  height: 14px;
  text-align: center;
  white-space: nowrap;
}

.delta {
  font-size: 9px;
  font-family: var(--mono);
  color: var(--ink-faint);
  text-align: center;
  line-height: 1.3;
}

.delta.flat {
  color: var(--teal);
}

.facts {
  list-style: none;
  margin: 12px 0 0;
  padding: 9px 0 0;
  border-top: 1px solid var(--line-soft);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.facts li {
  display: flex;
  gap: 7px;
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--ink-mute);
}

.facts li b {
  color: var(--ink-soft);
  font-weight: 600;
  flex: none;
  min-width: 88px;
}

.facts li.same b {
  color: var(--teal);
}

@media (max-width: 760px) {
  .bars {
    min-height: 150px;
  }

  .bar-col {
    height: 150px;
  }

  .bar {
    width: 14px;
  }

  .bar.same {
    width: 30px;
  }

  .legend-note {
    display: none;
  }

  .facts li {
    flex-direction: column;
    gap: 1px;
  }

  .facts li b {
    min-width: 0;
  }
}
</style>
