/**
 * Database and HTTP failures may embed complete rows or request bodies.
 * Keep only diagnostic codes whose vocabulary cannot carry credentials.
 */
export function authFailureDetails(error: unknown): { failed: boolean; code?: string; status?: number } {
  const details: { failed: boolean; code?: string; status?: number } = { failed: error != null };
  if (error === null || typeof error !== "object") return details;
  if ("code" in error && typeof error.code === "string") {
    const knownCodes = new Set(["23505", "23503", "42501", "42P10", "42703", "PGRST116", "PGRST204"]);
    if (knownCodes.has(error.code)) details.code = error.code;
  }
  if ("status" in error && typeof error.status === "number" && Number.isInteger(error.status)) {
    if (error.status >= 100 && error.status <= 599) details.status = error.status;
  }
  return details;
}
