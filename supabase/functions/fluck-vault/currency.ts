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

/**
 * Active ISO 4217 codes with a two-digit minor unit. A fixed list rather than
 * `Intl.supportedValuesOf`, which a runtime may lack and which also lists codes with no minor
 * unit at all (XAU, XDR, XXX, ...) that must never render as an amount.
 */
const TWO_DIGIT_CODES: readonly string[] = [
  "AED AFN ALL AMD ANG AOA ARS AUD AWG AZN BAM BBD BDT BGN BMD BND BOB BOV BRL BSD",
  "BTN BWP BYN BZD CAD CDF CHE CHF CHW CNY COP COU CRC CUP CVE CZK DKK DOP DZD EGP",
  "ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GTQ GYD HKD HNL HTG HUF IDR ILS INR IRR",
  "JMD KES KGS KHR KPW KYD KZT LAK LBP LKR LRD LSL MAD MDL MGA MKD MMK MNT MOP MRU",
  "MUR MVR MWK MXN MXV MYR MZN NAD NGN NIO NOK NPR NZD PAB PEN PGK PHP PKR PLN QAR",
  "RON RSD RUB SAR SBD SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS",
  "TMT TOP TRY TTD TWD TZS UAH USD USN UYU UZS VED VES WST XCD XCG YER ZAR ZMW ZWG",
].join(" ").split(" ")

/** Every code this function knows, with its ISO 4217 minor unit exponent. */
export const MINOR_UNIT_EXPONENTS: Readonly<Record<string, number>> = Object.freeze({
  ...Object.fromEntries(TWO_DIGIT_CODES.map((code) => [code, 2])),
  ...NON_TWO_DIGIT_EXPONENTS,
})

/** True for an ISO 4217 code this function can render. */
export function isKnownCurrency(code: string): boolean {
  return /^[A-Z]{3}$/.test(code) && Object.hasOwn(MINOR_UNIT_EXPONENTS, code)
}

/** The ISO 4217 minor unit exponent, or null for a code this function does not know. */
export function minorUnitExponent(code: string): number | null {
  return isKnownCurrency(code) ? MINOR_UNIT_EXPONENTS[code] : null
}

/**
 * The largest amount accepted, in minor units. Well inside 2^53 so the double JSON parses into
 * is exact, and far above any purchase within a card's spending limit.
 */
export const MAX_MINOR_AMOUNT = 1_000_000_000_000
