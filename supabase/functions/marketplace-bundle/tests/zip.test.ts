import { assert, assertEquals, assertThrows } from "@std/assert";
import { type Zippable, zipSync } from "fflate";
import { BundleError } from "../manifest.ts";
import { checkEntryName, crc32, LIMITS, validateBundle } from "../zip.ts";
import { bundle, findSig, patchAll, skillBundle, skillManifest, text } from "./helpers.ts";

function code(fn: () => unknown): string {
  try {
    fn();
  } catch (e) {
    if (e instanceof BundleError) return e.code;
    throw e;
  }
  return "ok";
}

Deno.test("a valid skill bundle passes and returns only the manifest and file sizes", () => {
  const v = validateBundle(skillBundle());
  assertEquals(v.manifest.slug, "my-skill");
  assertEquals(v.files.get("SKILL.md"), text("# Skill\nDo the thing.\n").length);
  assert(v.files.has("boss.json"));
});

Deno.test("directories are implicit and directory entries are allowed", () => {
  const z: Zippable = {
    "boss.json": text(JSON.stringify(skillManifest())),
    "SKILL.md": text("x"),
    "docs/": new Uint8Array(0),
    "docs/a.md": text("a"),
  };
  assertEquals(code(() => validateBundle(zipSync(z))), "ok");
});

Deno.test("boss.json must exist, be UTF-8 JSON and stay under 64 KB", () => {
  assertEquals(code(() => validateBundle(zipSync({ "SKILL.md": text("x") }))), "invalid_bundle");
  assertEquals(
    code(() => validateBundle(zipSync({ "boss.json": text("{nope"), "SKILL.md": text("x") }))),
    "invalid_bundle",
  );
  assertEquals(
    code(() => validateBundle(zipSync({ "boss.json": text("[]"), "SKILL.md": text("x") }))),
    "invalid_manifest",
  );
  const big = skillManifest({ description: "x".repeat(4000) });
  const pad = { ...big, name: "My skill" } as Record<string, unknown>;
  const raw = JSON.stringify(pad).replace("}", `,"pad":"${"x".repeat(70000)}"}`);
  assertEquals(
    code(() => validateBundle(zipSync({ "boss.json": text(raw), "SKILL.md": text("x") }))),
    "invalid_bundle",
  );
  // A boss.json that is not at the root does not count.
  assertEquals(
    code(() =>
      validateBundle(
        zipSync({ "sub/boss.json": text(JSON.stringify(skillManifest())), "SKILL.md": text("x") }),
      )
    ),
    "invalid_bundle",
  );
});

Deno.test("the skill entry must exist and stay under 256 KB", () => {
  assertEquals(code(() => validateBundle(bundle(skillManifest(), {}))), "invalid_bundle");
  assertEquals(
    code(() =>
      validateBundle(
        bundle(skillManifest(), { "SKILL.md": new Uint8Array(256 * 1024 + 1).fill(97) }),
      )
    ),
    "invalid_bundle",
  );
  assertEquals(
    code(() =>
      validateBundle(bundle(skillManifest(), { "SKILL.md": new Uint8Array(256 * 1024).fill(97) }))
    ),
    "ok",
  );
});

Deno.test("zip-slip and unsafe names are refused", () => {
  for (
    const n of [
      "../evil.md",
      "a/../../b",
      "a/./b",
      "/abs.md",
      "~/x.md",
      "C:/x.md",
      "a\\b.md",
      "a//b.md",
      "a\u0000b",
      "a\nb.md",
      "",
    ]
  ) {
    assertThrows(() => checkEntryName(n), BundleError, undefined, JSON.stringify(n));
  }
  checkEntryName("docs/a b.md");
  checkEntryName("dir/");
});

Deno.test("a bundle that carries an unsafe name in the archive is refused", () => {
  for (const [bad, safe] of [["../evil.md", "SKILL.md"], ["/abs/evil.md", "SKILL.md"]]) {
    const z = zipSync({
      "boss.json": text(JSON.stringify(skillManifest())),
      "SKILL.md": text("x"),
      [bad]: text("e"),
    });
    assertEquals(code(() => validateBundle(z)), "invalid_bundle", bad + safe);
  }
  const backslash = patchAll(
    zipSync({
      "boss.json": text(JSON.stringify(skillManifest())),
      "SKILL.md": text("x"),
      "aXb.md": text("e"),
    }),
    "aXb.md",
    "a\\b.md",
  );
  assertEquals(code(() => validateBundle(backslash)), "invalid_bundle");
  const nul = patchAll(
    zipSync({
      "boss.json": text(JSON.stringify(skillManifest())),
      "SKILL.md": text("x"),
      "aXb.md": text("e"),
    }),
    "aXb.md",
    "a\u0000b.md",
  );
  assertEquals(code(() => validateBundle(nul)), "invalid_bundle");
});

Deno.test("symlinks and special files are refused", () => {
  const base = { "boss.json": text(JSON.stringify(skillManifest())), "SKILL.md": text("x") };
  const link: Zippable = {
    ...base,
    "link": [text("/etc/passwd"), { os: 3, attrs: 0o120777 << 16 }],
  };
  assertEquals(code(() => validateBundle(zipSync(link))), "invalid_bundle");
  const fifo: Zippable = { ...base, "pipe": [text(""), { os: 3, attrs: 0o010644 << 16 }] };
  assertEquals(code(() => validateBundle(zipSync(fifo))), "invalid_bundle");
  const regular: Zippable = {
    ...base,
    "script.sh": [text("echo"), { os: 3, attrs: 0o100755 << 16 }],
  };
  assertEquals(code(() => validateBundle(zipSync(regular))), "ok");
});

Deno.test("duplicate names are refused, exact or by case", () => {
  const base = { "boss.json": text(JSON.stringify(skillManifest())), "SKILL.md": text("x") };
  const exact = patchAll(
    zipSync({ ...base, "aa.md": text("1"), "ab.md": text("2") }),
    "ab.md",
    "aa.md",
  );
  assertEquals(code(() => validateBundle(exact)), "invalid_bundle");
  assertEquals(
    code(() => validateBundle(zipSync({ ...base, "Notes.md": text("1"), "notes.md": text("2") }))),
    "invalid_bundle",
  );
});

Deno.test("at most 200 entries", () => {
  const mk = (n: number) => {
    const z: Zippable = {
      "boss.json": text(JSON.stringify(skillManifest())),
      "SKILL.md": text("x"),
    };
    for (let i = 0; i < n - 2; i++) z[`f${i}.txt`] = text("a");
    return zipSync(z);
  };
  assertEquals(code(() => validateBundle(mk(LIMITS.entries))), "ok");
  assertEquals(code(() => validateBundle(mk(LIMITS.entries + 1))), "invalid_bundle");
});

Deno.test("size limits: 2 MB a file, 30 MB in all, 10 MB compressed", () => {
  const base = { "boss.json": text(JSON.stringify(skillManifest())), "SKILL.md": text("x") };
  const zeros = (n: number) => new Uint8Array(n);
  assertEquals(
    code(() => validateBundle(zipSync({ ...base, "a.bin": zeros(LIMITS.perFile) }))),
    "ok",
  );
  assertEquals(
    code(() => validateBundle(zipSync({ ...base, "a.bin": zeros(LIMITS.perFile + 1) }))),
    "invalid_bundle",
  );
  // 15 x 2 MB = 30 MB is the limit; one more file crosses it. Zeros compress to a few KB each.
  const many = (n: number) => {
    const z: Zippable = { ...base };
    for (let i = 0; i < n; i++) z[`z${i}.bin`] = zeros(LIMITS.perFile);
    return zipSync(z);
  };
  assertEquals(code(() => validateBundle(many(14))), "ok");
  assertEquals(code(() => validateBundle(many(16))), "invalid_bundle");
  const noise = new Uint8Array(LIMITS.compressed + 1);
  for (let i = 0; i < noise.length; i += 65536) {
    crypto.getRandomValues(noise.subarray(i, Math.min(i + 65536, noise.length)));
  }
  assertEquals(
    code(() => validateBundle(zipSync({ ...base, "n.bin": [noise, { level: 0 }] }))),
    "bundle_too_large",
  );
});

Deno.test("a lying header is caught: declared size, CRC, method, encryption", () => {
  const z = skillBundle();
  const cd = findSig(z, 0x02014b50);
  const patched = (offset: number, bytes: number[], at = cd) => {
    const c = z.slice();
    c.set(bytes, at + offset);
    return c;
  };
  // Declared uncompressed size smaller than the real one (first central entry is boss.json).
  assertEquals(code(() => validateBundle(patched(24, [1, 0, 0, 0]))), "invalid_bundle");
  // Wrong CRC.
  assertEquals(code(() => validateBundle(patched(16, [1, 2, 3, 4]))), "invalid_bundle");
  // Unsupported compression method (bzip2 = 12).
  assertEquals(code(() => validateBundle(patched(10, [12, 0]))), "invalid_bundle");
  // Encrypted flag.
  assertEquals(code(() => validateBundle(patched(8, [1, 0]))), "invalid_bundle");
  // Zip64 marker in a size.
  assertEquals(code(() => validateBundle(patched(24, [255, 255, 255, 255]))), "invalid_bundle");
});

Deno.test("a deflate bomb with a small declared size cannot expand past it", () => {
  const z = skillBundle();
  const bomb = zipSync({
    "boss.json": text(JSON.stringify(skillManifest())),
    "SKILL.md": text("x"),
    "big.bin": new Uint8Array(2 * 1024 * 1024),
  });
  const cd = findSig(bomb, 0x02014b50);
  // Find the big.bin central entry and shrink its declared size to 10 bytes.
  const dv = new DataView(bomb.buffer, bomb.byteOffset, bomb.byteLength);
  let p = cd;
  let hit = -1;
  while (p >= 0 && dv.getUint32(p, true) === 0x02014b50) {
    const nameLen = dv.getUint16(p + 28, true);
    const name = new TextDecoder().decode(bomb.subarray(p + 46, p + 46 + nameLen));
    if (name === "big.bin") hit = p;
    p += 46 + nameLen + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true);
  }
  assert(hit > 0);
  const c = bomb.slice();
  new DataView(c.buffer).setUint32(hit + 24, 10, true);
  assertEquals(code(() => validateBundle(c)), "invalid_bundle");
  assertEquals(code(() => validateBundle(z)), "ok");
});

Deno.test("trailing data, truncation and non-zip input are refused", () => {
  const z = skillBundle();
  assertEquals(code(() => validateBundle(new Uint8Array([...z, 1, 2, 3]))), "invalid_bundle");
  assertEquals(code(() => validateBundle(z.subarray(0, z.length - 10))), "invalid_bundle");
  assertEquals(code(() => validateBundle(text("not a zip at all, just text."))), "invalid_bundle");
  assertEquals(code(() => validateBundle(new Uint8Array(0))), "invalid_bundle");
});

Deno.test("crc32 matches the known value", () => {
  assertEquals(crc32(text("123456789")), 0xcbf43926);
});
