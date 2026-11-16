/**
 * Zip validation for marketplace bundles (contract section 1). The central directory is parsed and checked before
 * anything is inflated, so a hostile archive is refused on its metadata alone; fflate then inflates only entries
 * that passed, into buffers sized from the validated headers, and sizes and CRCs are checked again afterwards.
 */
import { unzipSync } from "fflate";
import { BundleError, type Manifest, validateManifest } from "./manifest.ts";

export const LIMITS = {
  compressed: 10 * 1024 * 1024,
  entries: 200,
  perFile: 2 * 1024 * 1024,
  total: 30 * 1024 * 1024,
  manifest: 64 * 1024,
  name: 255,
} as const;

const invalid = (message: string): never => {
  throw new BundleError("invalid_bundle", message);
};

interface Entry {
  name: string;
  dir: boolean;
  method: number;
  crc: number;
  size: number;
  csize: number;
}

let CRC_TABLE: Uint32Array | null = null;
export function crc32(data: Uint8Array): number {
  if (!CRC_TABLE) {
    CRC_TABLE = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < data.length; i++) c = CRC_TABLE[(c ^ data[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// deno-lint-ignore no-control-regex
const BAD_NAME = /[\u0000-\u001f\u007f\\]/;

/** An entry name is a relative path of plain segments. Directories end in "/". */
export function checkEntryName(name: string): void {
  if (name.length === 0 || name.length > LIMITS.name) {
    invalid("an entry name has an invalid length");
  }
  if (BAD_NAME.test(name)) invalid("an entry name has a forbidden character");
  if (name.startsWith("/") || name.startsWith("~") || /^[A-Za-z]:/.test(name)) {
    invalid("an entry path is absolute");
  }
  const parts = name.endsWith("/") ? name.slice(0, -1).split("/") : name.split("/");
  for (const p of parts) {
    if (p === "" || p === "." || p === "..") invalid("an entry path is not a plain relative path");
  }
}

function readEntries(zip: Uint8Array): Entry[] {
  const dv = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  if (zip.length < 22) invalid("not a zip archive");
  let eocd = -1;
  for (let i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 0xffff); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) return invalid("not a zip archive");
  const disk = dv.getUint16(eocd + 4, true);
  const cdDisk = dv.getUint16(eocd + 6, true);
  const onDisk = dv.getUint16(eocd + 8, true);
  const total = dv.getUint16(eocd + 10, true);
  const cdSize = dv.getUint32(eocd + 12, true);
  const cdOff = dv.getUint32(eocd + 16, true);
  const comment = dv.getUint16(eocd + 20, true);
  if (disk !== 0 || cdDisk !== 0 || onDisk !== total) {
    invalid("multi-disk archives are not supported");
  }
  if (total === 0xffff || cdSize === 0xffffffff || cdOff === 0xffffffff) {
    invalid("zip64 archives are not supported");
  }
  if (eocd + 22 + comment !== zip.length) invalid("trailing data after the archive");
  if (total > LIMITS.entries) {
    throw new BundleError("invalid_bundle", `more than ${LIMITS.entries} entries`);
  }
  if (cdOff + cdSize > eocd) invalid("the central directory is out of range");

  const dec = new TextDecoder("utf-8", { fatal: true });
  const out: Entry[] = [];
  let p = cdOff;
  let totalSize = 0;
  const seen = new Set<string>();
  for (let n = 0; n < total; n++) {
    if (p + 46 > cdOff + cdSize || dv.getUint32(p, true) !== 0x02014b50) {
      invalid("the central directory is damaged");
    }
    const madeBy = dv.getUint16(p + 4, true);
    const flags = dv.getUint16(p + 8, true);
    const method = dv.getUint16(p + 10, true);
    const crc = dv.getUint32(p + 16, true);
    const csize = dv.getUint32(p + 20, true);
    const size = dv.getUint32(p + 24, true);
    const nameLen = dv.getUint16(p + 28, true);
    const extraLen = dv.getUint16(p + 30, true);
    const commentLen = dv.getUint16(p + 32, true);
    const diskStart = dv.getUint16(p + 34, true);
    const ext = dv.getUint32(p + 38, true);
    const local = dv.getUint32(p + 42, true);
    const end = p + 46 + nameLen + extraLen + commentLen;
    if (end > cdOff + cdSize) invalid("the central directory is damaged");
    let name: string;
    try {
      name = dec.decode(zip.subarray(p + 46, p + 46 + nameLen));
    } catch {
      return invalid("an entry name is not valid UTF-8");
    }
    checkEntryName(name);
    const dir = name.endsWith("/");
    if (flags & 0x1 || flags & 0x40) invalid("encrypted entries are not allowed");
    if (method !== 0 && method !== 8) invalid("an entry uses an unsupported compression method");
    if (csize === 0xffffffff || size === 0xffffffff || diskStart !== 0) {
      invalid("zip64 entries are not supported");
    }
    // Unix mode lives in the high half of the external attributes when the archive was made on Unix (3).
    if (madeBy >> 8 === 3) {
      const type = (ext >>> 16) & 0xf000;
      if (type === 0xa000) invalid("symbolic links are not allowed");
      if (type !== 0 && type !== 0x8000 && type !== 0x4000) {
        invalid("special files are not allowed");
      }
      if (dir !== (type === 0x4000) && type !== 0) invalid("an entry type does not match its name");
    }
    if (size > LIMITS.perFile) {
      throw new BundleError("invalid_bundle", "a file is larger than 2 MB");
    }
    if (dir && size !== 0) invalid("a directory entry has content");
    totalSize += size;
    if (totalSize > LIMITS.total) {
      throw new BundleError("invalid_bundle", "the bundle is larger than 30 MB uncompressed");
    }
    // Local header must agree with the central directory before any data is read.
    if (local + 30 > eocd || dv.getUint32(local, true) !== 0x04034b50) {
      invalid("a local header is damaged");
    }
    const lname = dv.getUint16(local + 26, true);
    const lextra = dv.getUint16(local + 28, true);
    let lnameStr: string;
    try {
      lnameStr = dec.decode(zip.subarray(local + 30, local + 30 + lname));
    } catch {
      return invalid("an entry name is not valid UTF-8");
    }
    if (lnameStr !== name) invalid("a local header does not match the central directory");
    if (local + 30 + lname + lextra + csize > cdOff) {
      invalid("an entry overlaps the central directory");
    }
    if (method === 0 && csize !== size) invalid("a stored entry has inconsistent sizes");
    const key = name.toLowerCase().replace(/\/$/, "");
    if (seen.has(key)) invalid("duplicate entry names");
    seen.add(key);
    out.push({ name, dir, method, crc, size, csize });
    p = end;
  }
  if (p !== cdOff + cdSize) invalid("the central directory is damaged");
  return out;
}

export interface ValidatedBundle {
  manifest: Manifest;
  /** Regular files in the archive: name -> uncompressed size. */
  files: Map<string, number>;
}

/** Validates a zip and its boss.json. Throws BundleError; never returns content beyond the manifest. */
export function validateBundle(zip: Uint8Array): ValidatedBundle {
  if (zip.length > LIMITS.compressed) {
    throw new BundleError("bundle_too_large", "The bundle is larger than 10 MB.");
  }
  const entries = readEntries(zip);
  const files = new Map<string, Entry>();
  for (const e of entries) if (!e.dir) files.set(e.name, e);
  const mf = files.get("boss.json");
  if (!mf) invalid("boss.json is missing at the root of the bundle");
  if (mf!.size > LIMITS.manifest) invalid("boss.json is larger than 64 KB");

  let extracted: Record<string, Uint8Array>;
  try {
    extracted = unzipSync(zip, {
      filter: (f) =>
        files.has(f.name) && f.originalSize <= LIMITS.perFile &&
        f.originalSize === files.get(f.name)!.size,
    });
  } catch {
    return invalid("the archive could not be read");
  }
  for (const [name, e] of files) {
    const data = extracted[name];
    if (!data || data.length !== e.size || crc32(data) !== e.crc) invalid("an entry is corrupt");
  }

  let raw: unknown;
  try {
    raw = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(extracted["boss.json"]));
  } catch {
    return invalid("boss.json is not valid JSON");
  }
  const { manifest, required } = validateManifest(raw);
  for (const r of required) {
    const f = files.get(r.path);
    if (!f) invalid(`a file named in boss.json is missing from the bundle`);
    if (f!.size > r.maxBytes) invalid(`a file named in boss.json is too large`);
  }
  return { manifest, files: new Map([...files].map(([n, e]) => [n, e.size])) };
}
