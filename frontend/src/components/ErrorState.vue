<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import { ApiError, isApiError, ResultCode } from '@/api/types'

const props = withDefaults(
  defineProps<{
    error: unknown
    retryText?: string
    compact?: boolean
  }>(),
  { retryText: '重试', compact: false },
)

const emit = defineEmits<{ retry: [] }>()

const code = computed(() => (isApiError(props.error) ? props.error.code : 0))
const kind = computed(() => (isApiError(props.error) ? props.error.kind : 'business'))

const title = computed(() => {
  if (!isApiError(props.error)) return '加载失败'
  switch (props.error.kind) {
    case 'timeout':
      return '请求超时'
    case 'network':
      return '连不上后端'
    case 'canceled':
      return '请求已取消'
    default:
      break
  }
  switch (code.value) {
    case ResultCode.MODEL_NOT_CONFIGURED:
      return '模型未配置'
    case ResultCode.MODEL_AUTH_FAILED:
      return '模型鉴权失败'
    case ResultCode.MODEL_RATE_LIMITED:
      return '模型限流'
    case ResultCode.MODEL_TIMEOUT:
      return '模型调用超时'
    case ResultCode.RAG_INDEX_EMPTY:
      return '索引未就绪'
    case ResultCode.SESSION_BUSY:
      return '会话正忙'
    case ResultCode.NOT_FOUND:
      return '资源不存在'
    default:
      return '加载失败'
  }
})

const hint = computed(() => {
  if (!isApiError(props.error)) {
    return props.error instanceof Error ? props.error.message : String(props.error)
  }
  const error = props.error as ApiError
  switch (error.kind) {
    case 'timeout':
      return `${error.message}。后端仍在处理时请稍后重试。`
    case 'network':
      return '后端服务未启动或地址配置有误。开发期默认 http://localhost:8080。'
    default:
      break
  }
  switch (error.code) {
    case ResultCode.MODEL_NOT_CONFIGURED:
      return `${error.detail}。检查 .env 中的模型三组配置（对话 / embedding / 编译）。`
    case ResultCode.RAG_INDEX_EMPTY:
      return `${error.detail}。到运维台重建索引后再试。`
    case ResultCode.SESSION_BUSY:
      return `${error.detail}。等这一轮结束后再发送。`
    default:
      return `${error.detail}（code=${error.code}）`
  }
})

const canRetry = computed(() => kind.value !== 'canceled')
</script>

<template>
  <div class="error-state" :class="{ 'error-state--compact': compact }" role="alert">
    <AppIcon name="alert" :size="18" class="error-state__icon" />
    <div class="error-state__body">
      <div class="error-state__title">{{ title }}</div>
      <p class="error-state__hint">{{ hint }}</p>
      <button v-if="canRetry" class="btn sm" @click="emit('retry')">{{ retryText }}</button>
    </div>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 16px;
  border: 1px solid var(--oxblood-line);
  background: var(--oxblood-soft);
  border-radius: var(--r-m);
}

.error-state--compact {
  padding: 10px 11px;
}

.error-state__icon {
  color: var(--oxblood);
  margin-top: 2px;
}

.error-state__title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--oxblood);
}

.error-state__hint {
  margin: 3px 0 9px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-soft);
}
</style>
