import assert from "node:assert/strict";
import { test } from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { resolveBuiltInModelInput, resolveCustomModelInput } = await jiti.import("../src/model-input.ts");

test("a built-in model the catalog calls text only can be told it reads images", () => {
  assert.deepEqual(resolveBuiltInModelInput(true, ["text"]), ["text", "image"]);
});

test("a built-in vision model is never downgraded by the flag", () => {
  assert.deepEqual(resolveBuiltInModelInput(false, ["text", "image"]), ["text", "image"]);
  assert.deepEqual(resolveBuiltInModelInput(undefined, ["text", "image"]), ["text", "image"]);
});

test("a built-in text only model stays text only when the flag is off or unset", () => {
  assert.deepEqual(resolveBuiltInModelInput(false, ["text"]), ["text"]);
  assert.deepEqual(resolveBuiltInModelInput(undefined, ["text"]), ["text"]);
});

test("a custom endpoint keeps images by default", () => {
  assert.deepEqual(resolveCustomModelInput(undefined), ["text", "image"]);
  assert.deepEqual(resolveCustomModelInput(true), ["text", "image"]);
});

test("a text only custom endpoint stops being sent images", () => {
  assert.deepEqual(resolveCustomModelInput(false), ["text"]);
});
