/**
 * Cross-repo contract: the payload the *client* actually produces must be accepted
 * here, and its properties must survive the vendor mapping.
 *
 * fixtures/client-payload.json is real output from
 * BossIngestSink.buildPayload in risa-labs-inc/boss-plugin-analytics, not a
 * hand-written approximation. The two sides live in different repositories and
 * neither suite can import the other, so this fixture is the seam: the validator here
 * is strict by design, and "strict" and "accepts what the client sends" are separate
 * claims that drift apart silently. A rejected batch would show up as telemetry
 * quietly stopping, with a 400 the client is right to treat as permanent.
 *
 * To regenerate after changing the sink's wire format, in the plugin repo:
 *   ./gradlew test --tests '*IngestContractTest*' -Dcontract.out=<path>
 * and copy the result here.
 */

import { assert, assertEquals } from "@std/assert"
import { type IngestEvent, toAmplitudePayload, validate } from "../app.ts"

const raw = await Deno.readTextFile(new URL("./fixtures/client-payload.json", import.meta.url))
const payload = JSON.parse(raw)

Deno.test("the client's real payload is accepted", () => {
  const result = validate(payload)
  assert(typeof result !== "string", `the live client payload was rejected: ${result}`)
  assertEquals(result.events.length, payload.events.length, "no event may be dropped")
})

Deno.test("the fixture exercises the event families the collector actually emits", () => {
  // A fixture that only carried one trivial event would pass the test above while
  // pinning nothing, so assert the interesting shapes are present.
  const names: string[] = payload.events.map((e: IngestEvent) => e.name)
  for (const expected of [
    "auth.signed_in",
    "browser.page_viewed",
    "browser.page_left",
    "browser.interaction.form_submitted",
    "browser.interaction.scroll_depth",
    "browser.interaction.click",
    "browser.interaction.text_selected",
    "workflow.completed",
    "tab.opened",
    "plugin.loaded",
  ]) {
    assert(names.includes(expected), `fixture is missing ${expected}`)
  }
  // A product event, so `source` is not always HOST_EVENT.
  assert(
    payload.events.some((e: IngestEvent) => e.source === "PRODUCT_EVENT"),
    "fixture should include a PluginContext.track() event",
  )
  // The two deliberately-renamed keys, which exist because PiiSanitizer matches its
  // deny list by suffix and would have stripped fieldName/elementPath silently.
  const form = payload.events.find((e: IngestEvent) => e.name.endsWith("form_submitted"))!
  // formField is deliberately NOT here. The collector omits page-controlled field names
  // outright: a form field's name is chosen by the page author, and neither identifier syntax
  // nor a trailing token nor an unsalted hash reliably keeps personal data out of it, so a
  // rename cannot make the value safe. The plugin's own IngestContractTest pins the same
  // absence; these two files are the only thing holding the two repos to one answer.
  assert(!("formField" in form.properties), "page-controlled formField must not be sent")
  assert("elementPosition" in form.properties, "elementPosition must survive")

  // The same suffix trap, on the properties added later. `linkUrl`, `isExternalLink` and
  // `selectedText` would each have been stripped client-side without a word of complaint.
  const click = payload.events.find((e: IngestEvent) => e.name.endsWith("interaction.click"))!
  assert("linkTargetDomain" in click.properties, "linkTargetDomain must survive")
  assert("linkKind" in click.properties, "linkKind must survive")

  const selection = payload.events.find((e: IngestEvent) => e.name.endsWith("text_selected"))!
  assert("selectionCharBucket" in selection.properties, "selectionCharBucket must survive")
  assert(
    typeof selection.properties.selectionCharBucket === "number",
    "selection metrics must stay numeric - a bucket rendered as a string meets the client sanitizer's DATE and PHONE patterns",
  )

  // The route carries page structure and must never carry a raw segment or a slash: the
  // client's own scrubber drops any value containing one, so a `/`-joined route arrives
  // as an absent property rather than as data.
  const view = payload.events.find((e: IngestEvent) => e.name === "browser.page_viewed")!
  assert("route" in view.properties, "route must survive")
  assert(
    !String(view.properties.route).includes("/"),
    "a route containing a slash would be dropped by the client sanitizer",
  )
})

Deno.test("every client property survives the vendor mapping", () => {
  const result = validate(payload)
  assert(typeof result !== "string")
  const mapped = JSON.parse(toAmplitudePayload("amp_key", result))

  for (const [i, source] of (payload.events as IngestEvent[]).entries()) {
    const props = mapped.events[i].event_properties
    for (const [k, v] of Object.entries(source.properties)) {
      assertEquals(props[k], v, `property '${k}' on ${source.name} was lost or altered`)
    }
    assertEquals(mapped.events[i].insert_id, source.insertId, "insert_id must pass through")
    assertEquals(mapped.events[i].time, source.timestampMs)
  }
})

Deno.test("no full URL, path, or free text appears anywhere in the fixture", () => {
  // The client is the privacy boundary, but a fixture is also the clearest place to
  // notice if that ever stops being true.
  assert(!/https?:\/\//.test(raw), "a full URL reached the ingest payload")
  assert(!/\/Users\/|\/home\/|[A-Z]:\\\\/.test(raw), "a filesystem path reached the ingest payload")
  assert(!/@[a-z0-9-]+\.[a-z]{2,}/i.test(raw), "something email-shaped reached the ingest payload")
})
