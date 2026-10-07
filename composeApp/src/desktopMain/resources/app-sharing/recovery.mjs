export const SHARING_RECOVERY_MS = 30000;
export function transientSharingFailure(error) {
  return error?.status === 408 || error?.status === 429 || (error?.status >= 500 && error?.status <= 599) ||
    error?.name === 'TypeError' || error?.name === 'TimeoutError' ||
    error?.message === 'Sharing request timed out';
}
