import { describe, it, expect } from "vitest";
import {
  baseCiteKey,
  surnameOf,
  titleWord,
  uniqueCiteKey,
  yearOf,
} from "../src/citekey.js";

describe("surnameOf", () => {
  it("handles 'First Last'", () => {
    expect(surnameOf("Ashish Vaswani")).toBe("vaswani");
  });
  it("handles 'Last, First'", () => {
    expect(surnameOf("Vaswani, Ashish")).toBe("vaswani");
  });
  it("strips diacritics", () => {
    expect(surnameOf("José Peña")).toBe("pena");
  });
  it("handles a single token", () => {
    expect(surnameOf("Aristotle")).toBe("aristotle");
  });
  it("returns empty for blank", () => {
    expect(surnameOf("   ")).toBe("");
  });
});

describe("yearOf", () => {
  it("extracts a year from an ISO date", () => {
    expect(yearOf("2017-06-12")).toBe("2017");
  });
  it("extracts a year from slash format", () => {
    expect(yearOf("2017/06/12")).toBe("2017");
  });
  it("returns 'nd' when absent", () => {
    expect(yearOf(undefined)).toBe("nd");
    expect(yearOf("no date here")).toBe("nd");
  });
});

describe("titleWord", () => {
  it("skips stopwords", () => {
    expect(titleWord("The Rise of Transformers")).toBe("rise");
  });
  it("falls back to the first word when all are stopwords", () => {
    expect(titleWord("On the")).toBe("on");
  });
});

describe("baseCiteKey", () => {
  it("builds author+year+word", () => {
    expect(
      baseCiteKey({
        authors: ["Ashish Vaswani"],
        publishedDate: "2017-06-12",
        title: "Attention Is All You Need",
      }),
    ).toBe("vaswani2017attention");
  });
  it("uses container when no author", () => {
    expect(
      baseCiteKey({ container: "Nature", publishedDate: "2020", title: "CRISPR advances" }),
    ).toBe("nature2020crispr");
  });
  it("falls back to anon + nd (no author, no date)", () => {
    expect(baseCiteKey({ title: "" })).toBe("anonnd");
  });
});

describe("uniqueCiteKey", () => {
  it("returns the base when free", () => {
    expect(uniqueCiteKey({ authors: ["Smith"], publishedDate: "2020", title: "Neural" }, new Set())).toBe(
      "smith2020neural",
    );
  });
  it("appends letters on collision", () => {
    const existing = new Set(["smith2020neural"]);
    expect(
      uniqueCiteKey({ authors: ["Smith"], publishedDate: "2020", title: "Neural" }, existing),
    ).toBe("smith2020neurala");
  });
  it("keeps incrementing past the first collision", () => {
    const existing = new Set(["smith2020neural", "smith2020neurala"]);
    expect(
      uniqueCiteKey({ authors: ["Smith"], publishedDate: "2020", title: "Neural" }, existing),
    ).toBe("smith2020neuralb");
  });
});
