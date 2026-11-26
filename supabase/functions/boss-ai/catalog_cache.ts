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

export interface CatalogCacheOptions {
  ttlMs?: number
  maxStaleMs?: number
  maxEntries?: number
  staleWaitMs?: number
  now?: () => number
  /** Called when an expired answer is served because the refresh failed or was slow. */
  onStale?: (reason: "error" | "timeout") => void
}

interface Entry {
  data: unknown
  at: number
}

class StaleWaitElapsed extends Error {}

export class CatalogCache {
  readonly #entries = new Map<string, Entry>()
  readonly #inflight = new Map<string, Promise<unknown>>()
  readonly #ttlMs: number
  readonly #maxStaleMs: number
  readonly #maxEntries: number
  readonly #staleWaitMs: number
  readonly #now: () => number
  readonly #onStale?: (reason: "error" | "timeout") => void

  constructor(options: CatalogCacheOptions = {}) {
    this.#ttlMs = options.ttlMs ?? CATALOG_CACHE_TTL_MS
    this.#maxStaleMs = options.maxStaleMs ?? CATALOG_CACHE_MAX_STALE_MS
    this.#maxEntries = options.maxEntries ?? CATALOG_CACHE_MAX_ENTRIES
    this.#staleWaitMs = options.staleWaitMs ?? CATALOG_CACHE_STALE_WAIT_MS
    this.#now = options.now ?? Date.now
    this.#onStale = options.onStale
  }

  get size(): number {
    return this.#entries.size
  }

  /**
   * The catalog for `user`: the cached answer while it is fresh, otherwise `load()`. Concurrent
   * calls for one user share a single `load()`. When the load fails, or outlasts the stale wait,
   * an expired answer no older than the stale limit is served instead; with none, the caller gets
   * the load's own outcome, as before the cache existed.
   */
  async get(user: string, load: () => Promise<unknown>): Promise<unknown> {
    if (!user) return await load()
    const cached = this.#entries.get(user)
    const age = cached ? this.#now() - cached.at : Infinity
    if (cached && age >= 0 && age < this.#ttlMs) {
      this.#touch(user, cached)
      return cached.data
    }
    const fresh = this.#refresh(user, load)
    if (!cached || age < 0 || age >= this.#maxStaleMs) return await fresh

    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      return await Promise.race([
        fresh,
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new StaleWaitElapsed()), this.#staleWaitMs)
        }),
      ])
    } catch (error) {
      // A slow load keeps running and stores its answer for the next caller.
      this.#onStale?.(error instanceof StaleWaitElapsed ? "timeout" : "error")
      return cached.data
    } finally {
      clearTimeout(timer)
    }
  }

  #refresh(user: string, load: () => Promise<unknown>): Promise<unknown> {
    const running = this.#inflight.get(user)
    if (running) return running
    const started = (async () => {
      try {
        const data = await load()
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
