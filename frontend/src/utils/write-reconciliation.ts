import { HttpError, RequestError, SessionChangedError, isUncertainWrite, sessionRevision } from '../api/http.ts'

export function canReconcileWrite(cause: unknown): boolean {
  return isUncertainWrite(cause) || cause instanceof HttpError && cause.status === 409
}

export async function reconcileWrite<T>(read: () => Promise<T>, matches: (snapshot: T) => boolean, isCurrent: () => boolean) {
  const revision = sessionRevision()
  let result = { snapshot: undefined as T | undefined, confirmed: false }
  for (const delay of [0, 400, 800]) {
    if (delay) await new Promise(resolve => setTimeout(resolve, delay))
    if (!isCurrent() || revision !== sessionRevision()) return
    try {
      const snapshot = await read()
      if (!isCurrent() || revision !== sessionRevision()) return
      result = { snapshot, confirmed: matches(snapshot) }
      if (result.confirmed) break
    } catch (cause) {
      if (cause instanceof SessionChangedError || !isCurrent() || revision !== sessionRevision()) return
      if (!(cause instanceof RequestError || cause instanceof HttpError)) throw cause
    }
  }
  return result
}
