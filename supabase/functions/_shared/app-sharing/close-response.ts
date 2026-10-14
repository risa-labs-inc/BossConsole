/** Only closure may treat an already absent, explicitly identified resource as success. */
export function validCloseResponse(
  path: string,
  body: Record<string, unknown>,
  value: Record<string, unknown>,
): boolean {
  const field = path.endsWith("tracks/close") ? "tracks" : "dataChannels";
  const key = field === "tracks" ? "mid" : "id";
  const expected = body[field], actual = value[field];
  if (
    value.errorCode || !Array.isArray(expected) || !Array.isArray(actual) ||
    actual.length !== expected.length
  ) return false;
  const ids = new Set(expected.map((entry) => entry[key]));
  if (ids.size !== expected.length) return false;
  for (const entry of actual) {
    if (
      !entry || !ids.delete(entry[key]) ||
      (entry.errorCode &&
        !["close_track_error", "track_not_found"].includes(entry.errorCode))
    ) return false;
  }
  return ids.size === 0;
}
