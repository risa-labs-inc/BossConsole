/**
 * The Max theme validator (theme.ts, a port of boss-plugin-max ThemePackage.kt) and theme bundles end to end.
 * The fixture is boss-plugin-max's samples/themes/ember/theme.json; the first tests mirror ThemePackageTest.kt.
 */
import { assert, assertEquals, assertFalse } from "@std/assert";
import { zipSync } from "fflate";
import ember from "./fixtures/ember.theme.json" with { type: "json" };
import { BundleError } from "../manifest.ts";
import {
  contrast,
  isBackdrop,
  isColour,
  isWoff2,
  ktTrim,
  parseTheme,
  REQUIRED,
  safeSvg,
} from "../theme.ts";
import { validateBundle } from "../zip.ts";
import { bundle, text } from "./helpers.ts";

const sample = JSON.stringify(ember, null, 2);
type Json = Record<string, unknown>;
const clone = (): Json => JSON.parse(sample);

function refused(
  raw: string,
  files = new Map<string, Uint8Array>(),
  expectedId: string | null = null,
): string {
  try {
    parseTheme(raw, files, expectedId);
  } catch (e) {
    if (e instanceof BundleError && e.code === "invalid_theme") return e.message;
    throw e;
  }
  throw new Error("expected the theme to be refused");
}
const refusedObj = (o: Json, files?: Map<string, Uint8Array>) => refused(JSON.stringify(o), files);

const woff2 = () => new Uint8Array([0x77, 0x4f, 0x46, 0x32, ...new Array(12).fill(0)]);

// ---- ThemePackageTest.kt ----------------------------------------------------

Deno.test("the sample theme parses with both schemes", () => {
  const t = parseTheme(sample, new Map(), "ember");
  assertEquals(t.id, "ember");
  assertEquals(t.name, "Ember");
  assertEquals(t.radius, "soft");
  assert(REQUIRED.every((r) => r in t.light && r in t.dark));
});

Deno.test("an id other than the listing's is refused", () => {
  assertEquals(refused(sample, new Map(), "other"), "id must be `other`.");
});

Deno.test("CSS injection in any value is refused", () => {
  for (
    const bad of [
      "url(https://x.example/a.png)",
      "red; } body { display:none",
      "#fff/**/",
      "expression(alert(1))",
      "var(--x)",
      "@import",
    ]
  ) {
    const o = clone();
    ((o.tokens as Json).light as Json).bg = bad;
    assert(refusedObj(o).startsWith("tokens.light.bg"), bad);
  }
  const o = clone();
  (o.backdrop as Json).light = "url(x)";
  assert(refusedObj(o).startsWith("backdrop.light"));
});

Deno.test("unknown tokens are refused, not ignored", () => {
  const o = clone();
  ((o.tokens as Json).light as Json).position = "#fff";
  assertEquals(refusedObj(o), "tokens.light.position is not a theme token.");
});

Deno.test("unreadable text fails the contrast gate", () => {
  const o = clone();
  ((o.tokens as Json).light as Json).text = "#f0f0f0";
  const msg = refusedObj(o);
  assert(msg.includes("text on bg") && msg.includes("4.5:1"), msg);
  assert(msg.startsWith("light: text on bg is 1."), msg);
});

Deno.test("SVG art with script or external links is unsafe", () => {
  assert(safeSvg(text("<svg xmlns='http://www.w3.org/2000/svg'><path d='M0 0L1 1'/></svg>")));
  assertFalse(safeSvg(text("<svg><script>alert(1)</script></svg>")));
  assertFalse(safeSvg(text("<svg><a href='https://x.example'>x</a></svg>")));
  assertFalse(safeSvg(text("<svg onload='x()'></svg>")));
  assertFalse(safeSvg(text("<svg><foreignObject/></svg>")));
  assert(safeSvg(text("<svg><a href='#top'>x</a></svg>")));
  assertFalse(safeSvg(text(`<svg>${" ".repeat(32 * 1024)}</svg>`)));
});

Deno.test("fonts must be woff2", () => {
  assert(isWoff2(woff2()));
  assertFalse(isWoff2(text("wOF2")));
  assertFalse(isWoff2(text("<html>")));
});

// ---- The grammar, piece by piece ----------------------------------------------

Deno.test("colours: hex, rgb/rgba/hsl/hsla and transparent; nothing else", () => {
  for (
    const ok of [
      "#fff",
      "#ffff",
      "#a8441f",
      "#a8441f80",
      "rgb(1, 2, 3)",
      "rgba(1 2 3 / 50%)",
      "hsl(10deg 50% 50%)",
      "HSLA(10,50%,50%,0.5)",
      "transparent",
    ]
  ) {
    assert(isColour(ok), ok);
  }
  for (
    const bad of [
      "#ff",
      "#fffff",
      "red",
      "rgb(1,2)",
      "rgb(1,2,3,4,5)",
      "calc(1px)",
      "#" + "f".repeat(70),
    ]
  ) {
    assertFalse(isColour(bad), bad);
  }
});

Deno.test("backdrops: up to four linear/radial gradients of colours, numbers and position words", () => {
  assert(isBackdrop((ember.backdrop as Json).light as string));
  assert(isBackdrop("linear-gradient(to bottom, #000, #fff)"));
  assertFalse(isBackdrop("linear-gradient(#000)"), "one colour");
  assertFalse(isBackdrop("conic-gradient(#000, #fff)"));
  assertFalse(isBackdrop("linear-gradient(to bottom, #000, #fff) repeat"));
  assertFalse(isBackdrop(Array(5).fill("linear-gradient(#000, #fff)").join(", ")), "five layers");
  assertFalse(isBackdrop("linear-gradient(to bottom, #000, #fff)!important"));
  assertFalse(isBackdrop("linear-gradient(to bottom, #000, #fff"), "unbalanced");
  assertFalse(isBackdrop("   "));
});

Deno.test("contrast follows WCAG", () => {
  assertEquals(Number(contrast("#000", "#fff")!.toFixed(2)), 21);
  assertEquals(contrast("#000", "transparent"), null);
  assertEquals(Number(contrast("hsl(0, 0%, 0%)", "rgb(255, 255, 255)")!.toFixed(2)), 21);
});

Deno.test("Kotlin trim: Unicode spaces go, a BOM stays", () => {
  assertEquals(ktTrim("  x  "), "x");
  assertEquals(ktTrim("﻿x"), "﻿x");
});

Deno.test("structure: required parts, allowed keys, author url, radius, preview", () => {
  const cases: Array<[(o: Json) => void, string]> = [
    [(o) => delete o.preview, "preview is required."],
    [(o) => (o.extra = 1), "theme.extra is not allowed."],
    [(o) => (o.schema = 2), "schema must be 1."],
    [(o) => (o.schema = "1"), "schema must be 1."],
    [(o) => delete o.author, 'author is required: {"name": ...}.'],
    [(o) => ((o.author as Json).url = "http://x.example"), "author.url must be an https address."],
    [(o) => (o.radius = "pill"), "radius: sharp, default, soft or round."],
    [(o) => (o.backdrop = null), "backdrop must be an object."],
    [(o) => (o.fonts = []), "fonts must be an object."],
    [(o) => delete ((o.tokens as Json).dark as Json).bg, "tokens.dark needs bg."],
    [(o) => (o.name = "x".repeat(41)), "name: 1 to 40 characters."],
    [(o) => (o.name = "Em‮ber"), "name: 1 to 40 characters."],
    [
      (o) => (o.id = "Ember"),
      "id: 2 to 31 lowercase letters, digits and dashes, starting with a letter.",
    ],
    [
      (o) => (((o.preview as Json).dark as Json).sent = "url(x)"),
      "preview.dark.sent: a colour or gradient.",
    ],
    [(o) => (o.art = "other.svg"), "art: the only art file is art.svg."],
    [(o) => (o.art = "art.svg"), "art: art.svg is not in the theme."],
    [(o) => (o.fonts = { body: "fonts/Inter.ttf" }), "fonts.body: a fonts/<name>.woff2 file."],
    [
      (o) => (o.fonts = { body: "fonts/Inter.woff2" }),
      "fonts.body: fonts/Inter.woff2 is not in the theme.",
    ],
  ];
  for (const [edit, message] of cases) {
    const o = clone();
    edit(o);
    assertEquals(refusedObj(o), message);
  }
  // Present and valid, they pass.
  const o = clone();
  o.fonts = { body: "fonts/Inter.woff2" };
  o.art = "art.svg";
  const files = new Map([["fonts/Inter.woff2", woff2()], [
    "art.svg",
    text("<svg><path d='M0 0'/></svg>"),
  ]]);
  assertEquals(parseTheme(JSON.stringify(o), files).fonts, { body: "fonts/Inter.woff2" });
  assertEquals(
    refusedObj(o, new Map([...files, ["art.svg", text("<svg><script/></svg>")]])),
    "art.svg was refused: at most 32 KB, no scripts, links, images or event handlers.",
  );
  assertEquals(
    refused(JSON.stringify({ ...clone(), pad: "x".repeat(33 * 1024) })),
    "theme.json is larger than 32 KB.",
  );
  assertEquals(refused("[]"), "theme.json is not a JSON object.");
});

Deno.test("a key that is not a plain identifier is not echoed back", () => {
  const o = clone();
  o["<img src=x>"] = 1;
  assertEquals(refusedObj(o), "theme.(a key) is not allowed.");
});

// ---- Theme bundles ---------------------------------------------------------

const themeManifest = (over: Json = {}) => ({
  schema: 1,
  kind: "theme",
  slug: "ember",
  name: "Ember",
  summary: "Warm sand by day.",
  publisher_name: "BOSS samples",
  theme: { entry: "theme.json" },
  ...over,
});

function code(fn: () => unknown): string {
  try {
    fn();
  } catch (e) {
    if (e instanceof BundleError) return e.code;
    throw e;
  }
  return "ok";
}

Deno.test("a theme bundle with Ember passes; with fonts and art too", () => {
  const v = validateBundle(bundle(themeManifest(), { "theme.json": text(sample) }));
  assertEquals(v.manifest.kind, "theme");
  const o = clone();
  o.fonts = { body: "fonts/Inter.woff2", heading: "fonts/Fraunces.woff2" };
  o.art = "art.svg";
  const full = bundle(themeManifest(), {
    "theme.json": text(JSON.stringify(o)),
    "art.svg": text("<svg><path d='M0 0'/></svg>"),
    "fonts/Inter.woff2": woff2(),
    "fonts/Fraunces.woff2": woff2(),
  });
  assertEquals(code(() => validateBundle(full)), "ok");
});

Deno.test("theme bundles: the manifest shape", () => {
  const z = (m: unknown) => code(() => validateBundle(bundle(m, { "theme.json": text(sample) })));
  assertEquals(z(themeManifest({ theme: { entry: "other.json" } })), "invalid_manifest");
  assertEquals(z(themeManifest({ theme: { entry: "theme.json", extra: 1 } })), "invalid_manifest");
  assertEquals(z(themeManifest({ theme: undefined })), "invalid_manifest");
  assertEquals(z(themeManifest({ skill: { entry: "SKILL.md" } })), "invalid_manifest");
  assertEquals(z(themeManifest({ slug: "9ember" })), "invalid_slug");
  // A skill may not carry a theme key.
  assertEquals(
    code(() =>
      validateBundle(bundle({
        schema: 1,
        kind: "skill",
        slug: "my-skill",
        name: "My skill",
        summary: "x",
        publisher_name: "A",
        skill: { entry: "SKILL.md" },
        theme: { entry: "theme.json" },
      }, { "SKILL.md": text("# x") }))
    ),
    "invalid_manifest",
  );
});

Deno.test("theme bundles: theme.json is validated, its id must be the slug, and nothing else may ride along", () => {
  const z = (files: Record<string, Uint8Array>, m: unknown = themeManifest()) =>
    code(() => validateBundle(bundle(m, files)));
  assertEquals(z({}), "invalid_bundle"); // theme.json missing
  assertEquals(z({ "theme.json": text(sample) }, themeManifest({ slug: "dusk" })), "invalid_theme");
  const bad = clone();
  ((bad.tokens as Json).light as Json).bg = "url(x)";
  assertEquals(z({ "theme.json": text(JSON.stringify(bad)) }), "invalid_theme");
  assertEquals(z({ "theme.json": text(sample), "script.js": text("x") }), "invalid_bundle");
  assertEquals(z({ "theme.json": text(sample), "fonts/Evil.ttf": text("x") }), "invalid_bundle");
  assertEquals(
    z({
      "theme.json": text(sample),
      "fonts/A.woff2": woff2(),
      "fonts/B.woff2": woff2(),
      "fonts/C.woff2": woff2(),
    }),
    "invalid_bundle",
  );
  // Directory entries are fine.
  const withDir = zipSync({
    "boss.json": text(JSON.stringify(themeManifest())),
    "theme.json": text(sample),
    "fonts/": new Uint8Array(0),
  });
  assertEquals(code(() => validateBundle(withDir)), "ok");
});
