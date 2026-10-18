<script setup lang="ts">
import { useRouter } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'

const router = useRouter()
</script>

<template>
  <AppLayout>
    <div class="not-found">
      <h2 class="not-found__title">页面不存在</h2>
      <p class="not-found__desc">这个地址没有对应的视图，后端也不提供该路径。</p>
      <button class="btn primary sm" @click="router.push('/chat')">回到售后对话</button>
    </div>
  </AppLayout>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  height: 100%;
  color: var(--ink-mute);
}

.not-found__title {
  font-family: var(--serif);
  font-size: 20px;
  color: var(--ink);
  margin: 0;
}

.not-found__desc {
  margin: 0;
  font-size: 12.5px;
}
</style>
