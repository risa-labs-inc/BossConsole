/**
 * Mock Supabase Client for Testing
 *
 * Provides mock implementations of Supabase client methods
 * to test service functions without actual database calls
 */

import { SignJWT } from "jose"

// deno-lint-ignore no-explicit-any
type DatabaseError = any

export interface MockSupabaseResponse<T = unknown> {
  data: T | null
  error: DatabaseError | null
}

// deno-lint-ignore no-explicit-any
type QueryParams = Record<string, any>

/** A queued response, optionally scoped to the filters a query must carry */
interface QueuedResponse {
  response: MockSupabaseResponse
  match?: Record<string, unknown>
}

export interface MockQueryBuilder extends Promise<MockSupabaseResponse> {
  select: (columns: string) => MockQueryBuilder
  insert: (data: unknown) => MockQueryBuilder
  upsert: (data: unknown, options?: Record<string, unknown>) => MockQueryBuilder
  update: (data: unknown) => MockQueryBuilder
  delete: () => MockQueryBuilder
  eq: (column: string, value: unknown) => MockQueryBuilder
  is: (column: string, value: unknown) => MockQueryBuilder
  gt: (column: string, value: unknown) => MockQueryBuilder
  lt: (column: string, value: unknown) => MockQueryBuilder
  not: (column: string, operator: string, value: unknown) => MockQueryBuilder
  or: (filters: string) => MockQueryBuilder
  order: (column: string, options?: Record<string, unknown>) => MockQueryBuilder
  limit: (count: number) => MockQueryBuilder
  single: () => Promise<MockSupabaseResponse>
  maybeSingle: () => Promise<MockSupabaseResponse>
  then: <TResult1 = MockSupabaseResponse, TResult2 = never>(
    onfulfilled?: ((value: MockSupabaseResponse) => TResult1 | PromiseLike<TResult1>) | null,
    onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null
  ) => Promise<TResult1 | TResult2>
}

export class MockSupabaseClient {
  // Store responses by table.operation key for more granular control
  private mockResponses: Map<string, QueuedResponse[]> = new Map()
  private queryHistory: Array<{ table: string; operation: string; params: QueryParams }> = []
  // email -> user id, used by the auth stub when minting a session
  private authUsers: Map<string, string> = new Map()
  private pendingLinks: Map<string, string> = new Map()

  // access token -> the user it resolves to, for auth.getUser()
  private accessTokens: Map<string, { id: string; email?: string; role?: string }> = new Map()
  // when set, the Admin API session mint fails the way a transient outage would
  private authFailure: string | null = null

  /**
   * Register a user for the Admin API stub, so a session minted for `email`
   * carries `userId` as its subject.
   */
  mockAuthUser(email: string, userId: string): void {
    this.authUsers.set(email, userId)
  }

  /**
   * Make `token` resolve to a signed-in user, the way `auth.getUser(token)`
   * would for a real access token. Tokens not registered here are rejected,
   * which is what an expired, forged or anon-key token looks like.
   */
  mockAccessToken(token: string, user: { id: string; email?: string; role?: string }): void {
    this.accessTokens.set(token, { role: 'authenticated', ...user })
  }

  /**
   * Make the Admin API session mint fail, as a transient outage would.
   *
   * Without this the stub always succeeds, so "the ceremony survives a failed
   * mint" is not expressible — which is why a regression there went unnoticed.
   */
  mockAuthFailure(message = 'Admin API unavailable'): void {
    this.authFailure = message
  }

  /**
   * Stub of the pieces of `supabase.auth` that utils/jwt.ts uses:
   * `auth.admin.generateLink()` followed by `auth.verifyOtp()`.
   *
   * The access token is a real HS256 JWT carrying the claims the production
   * auth hook injects, so callers can decode it like the real thing.
   */
  get auth() {
    // deno-lint-ignore no-this-alias
    const client = this

    return {
      /** Stub of auth.getUser(jwt) — resolves only tokens registered with mockAccessToken */
      getUser: (token?: string) => {
        const user = token ? client.accessTokens.get(token) : undefined
        if (!user) {
          return Promise.resolve({
            data: { user: null },
            error: { message: 'invalid JWT: unable to parse or verify signature', status: 401 }
          })
        }
        return Promise.resolve({ data: { user }, error: null })
      },
      admin: {
        generateLink: (params: { type: string; email: string }) => {
          if (client.authFailure) {
            return Promise.resolve({ data: null, error: { message: client.authFailure } })
          }
          const hashedToken = `mock-hashed-token-${client.pendingLinks.size + 1}`
          client.pendingLinks.set(hashedToken, params.email)
          return Promise.resolve({
            data: { properties: { hashed_token: hashedToken } },
            error: null
          })
        }
      },
      verifyOtp: async (params: { token_hash: string; type: string }) => {
        if (client.authFailure) {
          return { data: null, error: { message: client.authFailure } }
        }
        const email = client.pendingLinks.get(params.token_hash)
        if (!email) {
          return { data: null, error: { message: 'Invalid token hash' } }
        }

        const userId = client.authUsers.get(email) ?? 'mock-user-id'
        const secret = new TextEncoder().encode(
          Deno.env.get('JWT_SECRET') || 'mock-jwt-secret-at-least-32-characters-long-for-tests'
        )

        const accessToken = await new SignJWT({
          sub: userId,
          email,
          role: 'authenticated',
          aal: 'aal1',
          amr: [{ method: 'passkey', timestamp: Math.floor(Date.now() / 1000) }]
        })
          .setProtectedHeader({ alg: 'HS256' })
          .setIssuer('supabase')
          .setAudience('authenticated')
          .setIssuedAt()
          .setExpirationTime('1h')
          .sign(secret)

        return {
          data: {
            session: {
              access_token: accessToken,
              refresh_token: `mock-refresh-token-${userId}`,
              expires_in: 3600
            }
          },
          error: null
        }
      }
    }
  }

  /**
   * Configure mock response for a specific table and operation
   * Multiple calls will queue responses (useful for sequential queries)
   *
   * `options.match` makes the response behave like an actual row: it is only
   * served to a query that filtered on those column/value pairs, and a query
   * with different filters gets "no rows found" instead. Without it the response
   * is served to any query on the same table/operation, which cannot distinguish
   * a lookup keyed on one column value from a lookup keyed on another.
   */
  mockResponse(
    table: string,
    response: MockSupabaseResponse,
    operation = 'default',
    options?: { match?: Record<string, unknown> }
  ): void {
    const key = `${table}.${operation}`
    const existing = this.mockResponses.get(key) || []
    existing.push({ response, match: options?.match })
    this.mockResponses.set(key, existing)
  }

  /**
   * Get query history for testing
   */
  getQueryHistory() {
    return this.queryHistory
  }

  /**
   * Drop the queued responses for one table/operation, so a test can replace a
   * shared fixture's expectations without clearing everything.
   */
  clearTableQueue(table: string, operation = 'default'): void {
    this.mockResponses.delete(`${table}.${operation}`)
  }

  /**
   * Clear all mocks
   */
  clearMocks(): void {
    this.mockResponses.clear()
    this.queryHistory = []
  }

  /**
   * Response for a query that matched no row, shaped like PostgREST's.
   */
  private static notFound(): MockSupabaseResponse {
    return { data: null, error: { code: 'PGRST116', message: 'No rows found (mock filter mismatch)' } }
  }

  /**
   * True when every column/value pair in `match` was filtered on by the query.
   */
  private static filtersSatisfy(match: Record<string, unknown>, params: QueryParams): boolean {
    const filters = [
      ...((params.eq ?? []) as Array<{ column: string; value: unknown }>),
      ...((params.is ?? []) as Array<{ column: string; value: unknown }>)
    ]

    return Object.entries(match).every(([column, value]) =>
      filters.some(filter => filter.column === column && filter.value === value)
    )
  }

  /**
   * Get next response for a table.operation key
   */
  private getNextResponse(table: string, operation: string, params: QueryParams = {}): MockSupabaseResponse {
    const queues = [
      `${table}.${operation}`,
      `${table}.default`,
      table // legacy key (backward compatibility)
    ]

    for (const key of queues) {
      const queue = this.mockResponses.get(key)
      if (!queue || queue.length === 0) continue

      // Filter-aware entries behave like rows: the query has to select them.
      if (queue.some(entry => entry.match)) {
        const index = queue.findIndex(
          entry => !entry.match || MockSupabaseClient.filtersSatisfy(entry.match, params)
        )

        if (index === -1) {
          // Queued rows exist but none match this query's filters
          return MockSupabaseClient.notFound()
        }

        return queue.splice(index, 1)[0].response
      }

      return queue.shift()!.response
    }

    return { data: null, error: null }
  }

  /**
   * Mock rpc() method for calling database functions
   */
  rpc(functionName: string, params?: Record<string, unknown>): Promise<MockSupabaseResponse> {
    this.queryHistory.push({ table: `rpc.${functionName}`, operation: 'call', params: params || {} })
    const response = this.getNextResponse(`rpc.${functionName}`, 'call')
    return Promise.resolve(response)
  }

  /**
   * Mock from() method
   */
  from(table: string): MockQueryBuilder {
    let currentOperation = ''
    const currentParams: QueryParams = {}

    const executeQuery = (): Promise<MockSupabaseResponse> => {
      this.queryHistory.push({ table, operation: currentOperation, params: currentParams })
      const response = this.getNextResponse(table, currentOperation, currentParams)
      return Promise.resolve(response)
    }

    const builder = {
      select: (columns: string) => {
        // If we already have an operation (like insert), this is a chained select
        // In that case, keep the original operation for mock lookup
        if (!currentOperation) {
          currentOperation = 'select'
        }
        currentParams.columns = columns
        return builder
      },
      insert: (data: unknown) => {
        currentOperation = 'insert'
        currentParams.data = data
        return builder
      },
      upsert: (data: unknown, options?: Record<string, unknown>) => {
        // Upserts resolve against the same queue as inserts: tests care about the
        // row that was written, not which statement wrote it.
        currentOperation = 'insert'
        currentParams.data = data
        currentParams.upsert = options ?? {}
        return builder
      },
      update: (data: unknown) => {
        currentOperation = 'update'
        currentParams.data = data
        return builder
      },
      delete: () => {
        currentOperation = 'delete'
        return builder
      },
      eq: (column: string, value: unknown) => {
        if (!currentParams.eq) {
          currentParams.eq = []
        }
        currentParams.eq.push({ column, value })
        return builder
      },
      is: (column: string, value: unknown) => {
        if (!currentParams.is) {
          currentParams.is = []
        }
        currentParams.is.push({ column, value })
        return builder
      },
      gt: (column: string, value: unknown) => {
        currentParams.gt = { column, value }
        return builder
      },
      lt: (column: string, value: unknown) => {
        currentParams.lt = { column, value }
        return builder
      },
      not: (column: string, operator: string, value: unknown) => {
        if (!currentParams.not) currentParams.not = []
        currentParams.not.push({ column, operator, value })
        return builder
      },
      or: (filters: string) => {
        currentParams.or = filters
        return builder
      },
      order: (column: string, options?: Record<string, unknown>) => {
        currentParams.order = { column, ...(options ?? {}) }
        return builder
      },
      limit: (count: number) => {
        currentParams.limit = count
        return builder
      },
      single: () => executeQuery(),
      maybeSingle: () => executeQuery(),
      // Make the builder thenable so it can be awaited directly
      then: <TResult1 = MockSupabaseResponse, TResult2 = never>(
        onfulfilled?: ((value: MockSupabaseResponse) => TResult1 | PromiseLike<TResult1>) | null,
        onrejected?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null
      ) => executeQuery().then(onfulfilled, onrejected)
    } as MockQueryBuilder

    return builder
  }
}

/**
 * Factory function to create mock Supabase client
 */
export function createMockSupabaseClient(): MockSupabaseClient {
  return new MockSupabaseClient()
}

/**
 * Mock passkey data
 */
export const mockPasskey = {
  id: 'passkey-123',
  user_id: 'user-456',
  credential_id: 'credential-abc',
  public_key: 'mock-public-key-base64',
  display_name: 'My Test Passkey',
  transports: ['internal'],
  created_at: '2024-01-01T00:00:00Z',
  last_used_at: '2024-01-01T00:00:00Z',
  active: true
}

/**
 * Mock challenge data
 */
export const mockChallenge = {
  id: 'challenge-789',
  challenge: 'mock-challenge-base64',
  type: 'authentication',
  user_id: 'user-456',
  session_id: 'session-xyz',
  expires_at: new Date(Date.now() + 60000).toISOString(),
  consumed: false,
  created_at: new Date().toISOString()
}

/**
 * Mock authentication credential
 */
export const mockAuthenticationCredential = {
  id: 'credential-abc',
  rawId: 'credential-abc-raw',
  type: 'public-key',
  response: {
    clientDataJSON: btoa(JSON.stringify({
      type: 'webauthn.get',
      challenge: 'mock-challenge-base64',
      origin: 'https://api.risaboss.com'
    })),
    authenticatorData: 'mock-authenticator-data-base64',
    signature: 'mock-signature-base64',
    userHandle: 'user-456'
  }
}

/**
 * Mock registration credential
 */
export const mockRegistrationCredential = {
  id: 'credential-new',
  rawId: 'credential-new-raw',
  type: 'public-key',
  response: {
    clientDataJSON: btoa(JSON.stringify({
      type: 'webauthn.create',
      challenge: 'mock-challenge-base64',
      origin: 'https://api.risaboss.com'
    })),
    attestationObject: 'mock-attestation-object-base64'
  }
}
