import assert from "node:assert/strict";
import { test } from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { resolveContextWindow, FALLBACK_CONTEXT_WINDOW } = await jiti.import("../src/context-window.ts");

test("an explicit window from the caller always wins", () => {
  assert.equal(
    resolveContextWindow({
      pi_provider_id: "deepseek",
      model_id: "deepseek-v4-flash",
      context_window: 64_000,
    }),
    64_000,
  );
});

test("a known model reads its real window from the Pi catalog", () => {
  const window = resolveContextWindow({
    pi_provider_id: "deepseek",
    model_id: "deepseek-v4-flash",
  });

  // The catalog says 1M; anything near Aether's old 128K default means the
  // lookup silently stopped working.
  assert.ok(window > FALLBACK_CONTEXT_WINDOW, `expected a catalog window, got ${window}`);
  assert.equal(window, 1_000_000);
});

test("an unknown model on a known provider falls back instead of guessing", () => {
  assert.equal(
    resolveContextWindow({ pi_provider_id: "deepseek", model_id: "deepseek-not-a-thing" }),
    FALLBACK_CONTEXT_WINDOW,
  );
});

test("a custom endpoint provider falls back", () => {
  assert.equal(
    resolveContextWindow({ pi_provider_id: "aether-abc123", model_id: "gpt-4o-mini" }),
    FALLBACK_CONTEXT_WINDOW,
  );
});

test("blank ids and zero windows fall back", () => {
  assert.equal(resolveContextWindow({ pi_provider_id: "", model_id: "" }), FALLBACK_CONTEXT_WINDOW);
  assert.equal(
    resolveContextWindow({ pi_provider_id: "deepseek", model_id: "deepseek-v4-flash", context_window: 0 }),
    1_000_000,
  );
});
