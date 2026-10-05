/**
 * The amount contract, end to end: every amount on the wire (`totalCents` on `POST /requests`,
 * `total_cents` in `fluck_vault_requests`, `limit_minor` sealed by the card page) is an integer
 * count of the currency's ISO 4217 minor unit, i.e. `major * 10 ** minorUnitExponent(code)`.
 * USD 487.32 is 48732, JPY 1,200 is 1200, KWD 1.250 is 1250.
 *
 * The exponent comes from this table, not from `Intl`, because CLDR display digits diverge from
 * ISO 4217 for some codes and the minter has to be able to reproduce the table exactly.
 * `tests/fixtures/amount-vectors.json` pins the vectors the minter is asserted against.
 */

/** ISO 4217 codes whose minor unit is not two digits. Every other active code is two. */
export const NON_TWO_DIGIT_EXPONENTS: Readonly<Record<string, number>> = {
  BIF: 0,
  CLP: 0,
  DJF: 0,
  GNF: 0,
  ISK: 0,
  JPY: 0,
  KMF: 0,
  KRW: 0,
  PYG: 0,
  RWF: 0,
  UGX: 0,
  UYI: 0,
  VND: 0,
  VUV: 0,
  XAF: 0,
  XOF: 0,
  XPF: 0,
  BHD: 3,
  IQD: 3,
  JOD: 3,
  KWD: 3,
  LYD: 3,
  OMR: 3,
  TND: 3,
  CLF: 4,
  UYW: 4,
}

const KNOWN: ReadonlySet<string> = (() => {
  const codes = new Set<string>(Object.keys(NON_TWO_DIGIT_EXPONENTS))
  try {
    for (const code of Intl.supportedValuesOf("currency")) codes.add(code)
  } catch { /* an older runtime: the table above still decides the exponent */ }
  return codes
})()

/** True for an ISO 4217 code this function can render. */
export function isKnownCurrency(code: string): boolean {
  return /^[A-Z]{3}$/.test(code) && KNOWN.has(code)
}

/** The ISO 4217 minor unit exponent, or null for a code this function does not know. */
export function minorUnitExponent(code: string): number | null {
  if (!isKnownCurrency(code)) return null
  return NON_TWO_DIGIT_EXPONENTS[code] ?? 2
}

/**
 * The largest amount accepted, in minor units. Well inside 2^53 so the double JSON parses into
 * is exact, and far above any purchase within a card's spending limit.
 */
export const MAX_MINOR_AMOUNT = 1_000_000_000_000
