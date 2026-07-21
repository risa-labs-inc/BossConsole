/**
 * Error Handler Utility
 *
 * Provides flexible wrapper functions to handle try-catch logic for service functions
 */

export interface ServiceResult<T = unknown> {
  success: boolean
  error?: string
  [key: string]: unknown
}

/**
 * Generic error handler that accepts a custom error response builder
 *
 * @param fn - The async function to wrap
 * @param onError - Function that builds the error response from the caught error
 * @param logPrefix - Optional prefix for console.error logging
 * @returns Wrapped function with error handling
 *
 * @example
 * ```typescript
 * // For status-based responses
 * export const checkAuthStatus = withGenericErrorHandler(
 *   async (supabase, sessionId) => {
 *     // business logic returning { status: 'pending' | 'completed' | 'expired' }
 *   },
 *   (error) => ({ status: 'error', message: error.message }),
 *   '🔍'
 * )
 *
 * // For success-based responses
 * export const deletePasskey = withGenericErrorHandler(
 *   async (supabase, userId, passkeyId) => {
 *     // business logic returning { success: boolean }
 *   },
 *   (error) => ({ success: false, error: error.message }),
 *   '🗑️'
 * )
 * ```
 */
export function withGenericErrorHandler<TArgs extends unknown[], TResult, TError>(
  fn: (...args: TArgs) => Promise<TResult>,
  onError: (error: Error) => TError,
  logPrefix = '❌'
): (...args: TArgs) => Promise<TResult | TError> {
  return async (...args: TArgs) => {
    try {
      return await fn(...args)
    } catch (error) {
      const err = error as Error
      console.error(`${logPrefix} Error:`, err)
      return onError(err)
    }
  }
}

/**
 * Simplified error handler for standard { success, error } pattern
 *
 * @param fn - The async function to wrap
 * @param errorMessage - Default error message if the function throws
 * @param logPrefix - Optional prefix for console.error logging (e.g., '❌', '🔥')
 * @returns Wrapped function that returns ServiceResult
 *
 * @example
 * ```typescript
 * export const listUserPasskeys = withErrorHandler(
 *   async (supabase: SupabaseClient, userId: string) => {
 *     const result = await getUserPasskeys(supabase, userId)
 *     if (!result.success) {
 *       return { success: false, error: result.error || 'Failed to fetch passkeys' }
 *     }
 *     return { success: true, passkeys: result.passkeys }
 *   },
 *   'Failed to list passkeys',
 *   '📋'
 * )
 * ```
 */
export function withErrorHandler<TArgs extends unknown[], TResult extends ServiceResult>(
  fn: (...args: TArgs) => Promise<TResult>,
  errorMessage: string,
  logPrefix = '❌'
): (...args: TArgs) => Promise<TResult | { success: false; error: string }> {
  return withGenericErrorHandler(
    fn,
    (error) => ({
      success: false,
      error: error.message || errorMessage
    }),
    `${logPrefix} ${errorMessage}`
  )
}

/**
 * Error handler for status-based responses (like checkAuthStatus)
 *
 * @param fn - The async function to wrap
 * @param errorMessage - Error message for logging
 * @param logPrefix - Optional prefix for console.error logging
 * @returns Wrapped function that returns status-based result or error status
 *
 * @example
 * ```typescript
 * export const checkAuthStatus = withStatusErrorHandler(
 *   async (supabase: SupabaseClient, sessionId: string) => {
 *     // Returns: { status: 'pending' | 'completed' | 'expired', ... }
 *   },
 *   'Failed to check auth status',
 *   '🔍'
 * )
 * ```
 */
export function withStatusErrorHandler<TArgs extends unknown[], TResult>(
  fn: (...args: TArgs) => Promise<TResult>,
  errorMessage: string,
  logPrefix = '❌'
): (...args: TArgs) => Promise<TResult | { status: 'error'; message: string }> {
  return withGenericErrorHandler(
    fn,
    (error) => ({
      status: 'error' as const,
      message: error.message
    }),
    `${logPrefix} ${errorMessage}`
  )
}
