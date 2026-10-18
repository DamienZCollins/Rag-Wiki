import { ref, shallowRef, type Ref, type ShallowRef } from 'vue'
import type { ConfirmOptions } from './types'

/** 二次确认。返回 Promise，调用方 await 后才知道用户是否确认，避免确认前就发请求 */
export interface OpsConfirm {
  open: Ref<boolean>
  options: ShallowRef<ConfirmOptions>
  ask: (options: ConfirmOptions) => Promise<boolean>
  accept: () => void
  cancel: () => void
}

export function useOpsConfirm(): OpsConfirm {
  const open = ref(false)
  const options = shallowRef<ConfirmOptions>({ title: '' })
  let settle: ((confirmed: boolean) => void) | null = null

  function finish(confirmed: boolean) {
    const pending = settle
    settle = null
    open.value = false
    pending?.(confirmed)
  }

  function ask(next: ConfirmOptions): Promise<boolean> {
    // 上一次没结算就再问一次时，先把上一次按取消处理，避免弹窗状态与 Promise 脱节
    if (settle) {
      const pending = settle
      settle = null
      pending(false)
    }
    options.value = next
    open.value = true
    return new Promise<boolean>((resolve) => {
      settle = resolve
    })
  }

  return {
    open,
    options,
    ask,
    accept: () => finish(true),
    cancel: () => finish(false),
  }
}
