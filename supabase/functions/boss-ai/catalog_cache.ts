/**
 * Per-user cache of the boss_ai_catalog result behind GET /v1/models and GET /v1/usage.
 *
 * The catalog is the function's most frequent database call (every client polls it) and its
 * heaviest. Its only input is the caller's user id, so the user id is the whole key: an entry is
 * read back only for the id it was stored under, and that id comes from the verified AI token.
 * Nothing here is consulted for inference: lookup, reservation and settlement stay live.
 */

/** How long a catalog answer is served without asking the database again. */
export const CATALOG_CACHE_TTL_MS = 5 * 60_000
/** Oldest answer that may still be served when the database fails or is slow. */
export const CATALOG_CACHE_MAX_STALE_MS = 30 * 60_000
/** Most users held at once; the least recently used entry is dropped first. */
export const CATALOG_CACHE_MAX_ENTRIES = 2_000
/** With an expired answer in hand, how long to wait for a fresh one before serving the old one. */
export const CATALOG_CACHE_STALE_WAIT_MS = 3_000
/**
 * Oldest cached answer GET /v1/usage may reuse. Usage reports current consumption, so it gets a far
 * shorter window than the model list and never the expired-answer fallback.
 */
export const USAGE_MAX_AGE_MS = 30_000

export interface CatalogCacheOptions {
  ttlMs?: number
  maxStaleMs?: number
  maxEntries?: number
  staleWaitMs?: number
  now?: () => number
}

/** Per-call limits: the two routes share entries but not their tolerance for age. */
export interface CatalogGetOptions {
  /** Oldest cached answer this call accepts without a load (default: the cache's TTL). */
  maxAgeMs?: number
  /** Whether an expired answer may be served when the load fails or is slow (default true). */
  allowStale?: boolean
}

export interface CatalogAnswer {
  /** Deep-frozen: one object is shared by every caller, so none of them may change it. */
  value: unknown
  /** Age of the answer when served; 0 for one loaded by (or joined by) this call. */
  ageMs: number
  /** True when an expired answer was served because the load failed or was slow. */
  stale: boolean
  /** Why the expired answer was served; present only when `stale`. */
  reason?: "error" | "timeout"
}

interface Entry {
  data: unknown
  at: number
}

class StaleWaitElapsed extends Error {}

/** Freezes a JSON-shaped value in place, so the one stored object can be handed to every caller. */
function deepFreeze<T>(value: T): T {
  if (value === null || typeof value !== "object" || Object.isFrozen(value)) return value
  Object.freeze(value)
  for (const child of Object.values(value)) deepFreeze(child)
  return value
}

export class CatalogCache {
  readonly #entries = new Map<string, Entry>()
  readonly #inflight = new Map<string, Promise<unknown>>()
  readonly #ttlMs: number
  readonly #maxStaleMs: number
  readonly #maxEntries: number
  readonly #staleWaitMs: number
  readonly #now: () => number

  constructor(options: CatalogCacheOptions = {}) {
    this.#ttlMs = options.ttlMs ?? CATALOG_CACHE_TTL_MS
    this.#maxStaleMs = options.maxStaleMs ?? CATALOG_CACHE_MAX_STALE_MS
    this.#maxEntries = options.maxEntries ?? CATALOG_CACHE_MAX_ENTRIES
    this.#staleWaitMs = options.staleWaitMs ?? CATALOG_CACHE_STALE_WAIT_MS
    this.#now = options.now ?? Date.now
  }

  get size(): number {
    return this.#entries.size
  }

  /**
   * The catalog for `user`: the cached answer while it is younger than `maxAgeMs`, otherwise
   * `load()`. Concurrent calls for one user share a single `load()`, and its answer is live for all
   * of them. When the load fails, or outlasts the stale wait, a call that allows it is served an
   * expired answer no older than the stale limit; otherwise, or with none, the caller gets the
   * load's own outcome, as before the cache existed.
   */
  async get(
    user: string,
    load: () => Promise<unknown>,
    options: CatalogGetOptions = {},
  ): Promise<CatalogAnswer> {
    if (!user) return { value: deepFreeze(await load()), ageMs: 0, stale: false }
    // A call may ask for a fresher answer than the TTL, never an older one.
    const maxAgeMs = Math.min(options.maxAgeMs ?? this.#ttlMs, this.#ttlMs)
    const cached = this.#entries.get(user)
    const age = cached ? this.#now() - cached.at : Infinity
    if (cached && age >= 0 && age < maxAgeMs) {
      this.#touch(user, cached)
      return { value: cached.data, ageMs: age, stale: false }
    }
    const fresh = this.#refresh(user, load)
    if (options.allowStale === false || !cached || age < 0 || age >= this.#maxStaleMs) {
      return { value: await fresh, ageMs: 0, stale: false }
    }

    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const value = await Promise.race([
        fresh,
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new StaleWaitElapsed()), this.#staleWaitMs)
        }),
      ])
      return { value, ageMs: 0, stale: false }
    } catch (error) {
      // A slow load keeps running and stores its answer for the next caller.
      const reason = error instanceof StaleWaitElapsed ? "timeout" : "error"
      return { value: cached.data, ageMs: age, stale: true, reason }
    } finally {
      clearTimeout(timer)
    }
  }

  #refresh(user: string, load: () => Promise<unknown>): Promise<unknown> {
    const running = this.#inflight.get(user)
    if (running) return running
    const started = (async () => {
      try {
        const data = deepFreeze(await load())
        this.#store(user, data)
        return data
      } finally {
        this.#inflight.delete(user)
      }
    })()
    // A caller that stopped waiting must not turn a late failure into an unhandled rejection.
    started.catch(() => {})
    this.#inflight.set(user, started)
    return started
  }

  #store(user: string, data: unknown) {
    this.#entries.delete(user)
    this.#entries.set(user, { data, at: this.#now() })
    while (this.#entries.size > this.#maxEntries) {
      const oldest = this.#entries.keys().next().value
      if (oldest === undefined) break
      this.#entries.delete(oldest)
    }
  }

  /** Map order is recency: a read moves the entry to the end. */
  #touch(user: string, entry: Entry) {
    this.#entries.delete(user)
    this.#entries.set(user, entry)
  }
}
