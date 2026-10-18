<script setup lang="ts">
import AppLayout from '@/layouts/AppLayout.vue'
import CompilePanel from '@/features/ops/CompilePanel.vue'
import IndexPanel from '@/features/ops/IndexPanel.vue'
import EvalPanel from '@/features/ops/EvalPanel.vue'

/**
 * 运维操作台。九个低频、有副作用的入口按「Wiki 编译与体检 / RAG 索引 / 评测与门禁」三组排布。
 * 所有结果都来自接口真实返回；同步接口在执行中只显示已等待时长，不做进度伪造。
 * 页面标题由 AppLayout 顶栏承担，这里不再重复 h1。
 */

const groups = [
  {
    key: 'wiki',
    title: 'Wiki 编译与体检',
    note: '离线批处理，改动条目产物，不在在线问答链路上',
  },
  {
    key: 'index',
    title: 'RAG 索引',
    note: '重建与别名切换都会改变在线检索实际命中的内容',
  },
  {
    key: 'eval',
    title: '评测与门禁',
    note: '跑批与基线固化占用模型额度，且会覆盖对照口径',
  },
] as const
</script>

<template>
  <AppLayout>
    <template #context>九个运维入口 · 有副作用的操作均需二次确认</template>

    <div class="ops-page">
      <header class="ops-intro">
        <p class="ops-intro__desc">
          低频、有副作用的操作统一放在这里，与日常阅读视图分开。这些接口都是同步的：后端没返回就是还没跑完，
          页面只显示真实的已等待时长，不做进度伪造。
        </p>
        <ul class="ops-notice">
          <li>
            编译、重建、别名切换、用例导入、跑批、基线固化在执行前都要二次确认，确认框写明影响范围与预计耗时。
          </li>
          <li>执行结果全部来自接口返回，未操作过的区域显示「尚未执行」，不预填任何数字。</li>
          <li>同一操作进行中按钮禁用，不会重复发起请求。</li>
        </ul>
      </header>

      <section
        v-for="group in groups"
        :key="group.key"
        class="ops-group"
        :aria-labelledby="`ops-group-${group.key}`"
      >
        <div class="ops-group__head">
          <h2 :id="`ops-group-${group.key}`" class="ops-group__title serif">{{ group.title }}</h2>
          <span class="ops-group__note">{{ group.note }}</span>
        </div>

        <CompilePanel v-if="group.key === 'wiki'" />
        <IndexPanel v-else-if="group.key === 'index'" />
        <EvalPanel v-else />
      </section>
    </div>
  </AppLayout>
</template>

<style scoped>
.ops-page {
  height: 100%;
  overflow: auto;
  padding: 20px 24px 40px;
  background: var(--paper-raised);
  scrollbar-width: thin;
}

.ops-intro {
  max-width: 1000px;
  margin-bottom: 20px;
}

.ops-intro__desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ink-mute);
  max-width: 780px;
}

.ops-notice {
  margin: 10px 0 0;
  padding: 9px 12px 9px 26px;
  border: 1px solid var(--line-soft);
  border-radius: var(--r-m);
  background: var(--paper);
  font-size: 11px;
  line-height: 1.75;
  color: var(--ink-mute);
  max-width: 860px;
}

.ops-notice li + li {
  margin-top: 2px;
}

.ops-group {
  margin-bottom: 22px;
}

.ops-group__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 7px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.ops-group__title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}

.ops-group__note {
  font-size: 11px;
  color: var(--ink-faint);
}

@media (max-width: 1080px) {
  .ops-page {
    padding: 16px 16px 32px;
  }
}

@media (max-width: 760px) {
  .ops-page {
    padding: 12px 12px 28px;
  }

  .ops-intro__desc {
    font-size: 11.5px;
  }
}
</style>
