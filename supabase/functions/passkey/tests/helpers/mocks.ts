/**
 * Mock Supabase Client for Testing
 *
 * Provides mock implementations of Supabase client methods
 * to test service functions without actual database calls
 */

// deno-lint-ignore no-explicit-any
type DatabaseError = any

export interface MockSupabaseResponse<T = unknown> {
  data: T | null
  error: DatabaseError | null
}

// deno-lint-ignore no-explicit-any
type QueryParams = Record<string, any>

export interface MockQueryBuilder extends Promise<MockSupabaseResponse> {
  select: (columns: string) => MockQueryBuilder
  insert: (data: unknown) => MockQueryBuilder
  update: (data: unknown) => MockQueryBuilder
  delete: () => MockQueryBuilder
  eq: (column: string, value: unknown) => MockQueryBuilder
  gt: (column: string, value: unknown) => MockQueryBuilder
  lt: (column: string, value: unknown) => MockQueryBuilder
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
  private mockResponses: Map<string, MockSupabaseResponse[]> = new Map()
  private queryHistory: Array<{ table: string; operation: string; params: QueryParams }> = []

  /**
   * Configure mock response for a specific table and operation
   * Multiple calls will queue responses (useful for sequential queries)
   */
  mockResponse(table: string, response: MockSupabaseResponse, operation = 'default'): void {
    const key = `${table}.${operation}`
    const existing = this.mockResponses.get(key) || []
    existing.push(response)
    this.mockResponses.set(key, existing)
  }

  /**
   * Get query history for testing
   */
  getQueryHistory() {
    return this.queryHistory
  }

  /**
   * Clear all mocks
   */
  clearMocks(): void {
    this.mockResponses.clear()
    this.queryHistory = []
  }

  /**
   * Get next response for a table.operation key
   */
  private getNextResponse(table: string, operation: string): MockSupabaseResponse {
    // Try specific operation first
    const specificKey = `${table}.${operation}`
    const specificResponses = this.mockResponses.get(specificKey)
    if (specificResponses && specificResponses.length > 0) {
      return specificResponses.shift()!
    }

    // Fall back to default
    const defaultKey = `${table}.default`
    const defaultResponses = this.mockResponses.get(defaultKey)
    if (defaultResponses && defaultResponses.length > 0) {
      return defaultResponses.shift()!
    }

    // Fall back to legacy key (backward compatibility)
    const legacyResponses = this.mockResponses.get(table)
    if (legacyResponses && legacyResponses.length > 0) {
      return legacyResponses.shift()!
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
      const response = this.getNextResponse(table, currentOperation)
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
      gt: (column: string, value: unknown) => {
        currentParams.gt = { column, value }
        return builder
      },
      lt: (column: string, value: unknown) => {
        currentParams.lt = { column, value }
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
