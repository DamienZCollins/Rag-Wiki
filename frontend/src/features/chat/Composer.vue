<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'

/**
 * 输入区。
 * 回合进行中禁用发送；生成中把发送按钮换成停止生成，触发 AbortController.abort()。
 */

const props = defineProps<{
  busy: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  send: [text: string]
  stop: []
}>()

const text = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)

const canSend = computed(() => text.value.trim().length > 0 && !props.busy && !props.disabled)

function autoGrow() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(120, el.scrollHeight)}px`
}

async function onInput() {
  autoGrow()
  await nextTick()
}

function submit() {
  if (!canSend.value) return
  const content = text.value
  text.value = ''
  void nextTick(() => autoGrow())
  emit('send', content)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    submit()
  }
}

function focus() {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <footer class="composer">
    <div class="composer-inner">
      <label class="sr-only" for="composer-input">输入回复或提问</label>
      <textarea
        id="composer-input"
        ref="inputRef"
        v-model="text"
        rows="1"
        data-testid="composer-input"
        placeholder="继续追问，或输入客户问题；Enter 发送，Shift + Enter 换行"
        :disabled="disabled"
        :aria-busy="busy"
        @input="onInput"
        @keydown="onKeydown"
      ></textarea>
      <div class="composer-bar">
        <span class="composer-status" role="status" aria-live="polite">
          <template v-if="busy">
            <span class="live-dot" aria-hidden="true"></span>
            正在生成，可随时停止
          </template>
          <template v-else-if="disabled">当前不可发送</template>
        </span>
        <button
          v-if="busy"
          class="send send--stop"
          type="button"
          data-testid="composer-stop"
          aria-label="停止生成"
          title="停止生成"
          @click="emit('stop')"
        >
          <AppIcon name="stop" :size="15" />
        </button>
        <button
          v-else
          class="send"
          type="button"
          data-testid="composer-send"
          aria-label="发送"
          title="发送"
          :disabled="!canSend"
          @click="submit"
        >
          <AppIcon name="send" :size="15" />
        </button>
      </div>
    </div>
    <div class="composer-hint">
      <span>回答只基于已发布知识条目，并保留引用。</span>
      <span><kbd>Enter</kbd> 发送 · <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行</span>
    </div>
  </footer>
</template>

<style scoped>
.composer {
  border-top: 1px solid var(--line);
  padding: 12px 22px 14px;
  background: var(--paper-raised);
  flex: none;
}

.composer-inner {
  max-width: 730px;
  margin: 0 auto;
  border: 1px solid var(--line-strong);
  border-radius: 11px;
  background: var(--paper-raised);
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.composer-inner:focus-within {
  border-color: var(--ink-mute);
  box-shadow: 0 0 0 3px var(--paper-sunken);
}

.composer textarea {
  display: block;
  width: 100%;
  border: 0;
  outline: none;
  background: transparent;
  resize: none;
  padding: 12px 14px 4px;
  font-size: 13px;
  line-height: 1.6;
  min-height: 44px;
  max-height: 120px;
}

.composer textarea::placeholder {
  color: var(--ink-faint);
}

.composer textarea:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px 8px;
}

.composer-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: var(--ink-mute);
  min-height: 20px;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal);
  animation: pulse 1.6s infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 1;
  }
}

.send {
  margin-left: auto;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--ink);
  color: var(--paper-raised);
  display: grid;
  place-items: center;
  transition:
    background 0.15s,
    opacity 0.15s;
}

.send:hover:not(:disabled) {
  background: var(--oxblood);
}

.send:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.send--stop {
  background: var(--oxblood);
}

.send--stop:hover {
  filter: brightness(0.92);
}

.composer-hint {
  max-width: 730px;
  margin: 6px auto 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 10px;
  color: var(--ink-faint);
  gap: 10px;
  flex-wrap: wrap;
}

.composer-hint kbd {
  font-family: var(--mono);
  font-size: 9.5px;
  border: 1px solid var(--line);
  border-radius: 3px;
  padding: 0 3px;
  background: var(--paper);
}

@media (max-width: 760px) {
  .composer {
    padding: 10px 12px 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .live-dot {
    animation: none;
  }
}
</style>
