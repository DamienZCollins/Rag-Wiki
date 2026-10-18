import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark'

const STORAGE_KEY = 'ragwiki.theme'

function readStored(): ThemeMode {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    if (value === 'light' || value === 'dark') {
      return value
    }
  } catch {
    // localStorage 不可用（隐私模式）时退回浅色
  }
  return 'light'
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<ThemeMode>(readStored())

  function apply(value: ThemeMode) {
    document.documentElement.dataset.theme = value
  }

  function setTheme(value: ThemeMode) {
    theme.value = value
  }

  function toggle() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }

  watch(
    theme,
    (value) => {
      apply(value)
      try {
        localStorage.setItem(STORAGE_KEY, value)
      } catch {
        // 写入失败不影响当前会话的显示
      }
    },
    { immediate: true },
  )

  return { theme, setTheme, toggle, apply }
})
