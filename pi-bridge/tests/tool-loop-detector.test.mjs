import assert from "node:assert/strict";
import { test } from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const { ToolLoopDetector } = await jiti.import("../src/tool-loop-detector.ts");

test("stays quiet for a normal session", () => {
  const detector = new ToolLoopDetector();
  const calls = [
    ["read", { path: "/a.txt" }],
    ["bash", { command: "ls" }],
    ["read", { path: "/b.txt" }],
    ["write", { path: "/c.txt", content: "hi" }],
    ["read", { path: "/a.txt" }],
    ["bash", { command: "pwd" }],
    ["read", { path: "/d.txt" }],
    ["read", { path: "/e.txt" }],
    ["bash", { command: "ls" }],
  ];
  for (const [tool, args] of calls) {
    assert.equal(detector.check(tool, args).level, "none");
    detector.record(tool, args, { ok: true });
  }
  assert.equal(detector.size, calls.length);
});

test("warns at the warning threshold", () => {
  const detector = new ToolLoopDetector({ warningThreshold: 3, criticalThreshold: 99 });
  for (let index = 0; index < 2; index += 1) {
    detector.record("bash", { command: "ls" }, "ok");
  }
  const result = detector.check("bash", { command: "ls" });
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "identical_call");
  assert.equal(result.repeatCount, 3);
  assert.equal(result.detail, "bash × 3");
});

test("escalates to critical when identical calls run back to back", () => {
  const detector = new ToolLoopDetector({ warningThreshold: 3, criticalThreshold: 5 });
  for (let index = 0; index < 4; index += 1) {
    detector.record("bash", { command: "ls" }, "ok");
  }
  const result = detector.check("bash", { command: "ls" });
  assert.equal(result.level, "critical");
  assert.equal(result.repeatCount, 5);
});

test("critical stays reachable with the default thresholds", () => {
  const detector = new ToolLoopDetector();
  for (let index = 0; index < 11; index += 1) {
    detector.record("bash", { command: "npm test" }, "ok");
  }
  const result = detector.check("bash", { command: "npm test" });
  assert.equal(result.level, "critical");
  assert.equal(result.repeatCount, 12);
});

test("interleaved identical calls warn but never abort", () => {
  const detector = new ToolLoopDetector();
  for (let round = 0; round < 5; round += 1) {
    detector.record("read", { path: "/a.txt" }, "ok");
    detector.record("bash", { command: `echo ${round}` }, "ok");
  }
  const result = detector.check("read", { path: "/a.txt" });
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "identical_call");
  assert.ok(result.repeatCount >= 6);
});

test("matches arguments regardless of key order and ignores different ones", () => {
  const detector = new ToolLoopDetector({ warningThreshold: 2, criticalThreshold: 4 });
  detector.record("read", { path: "/a.txt", limit: 10 }, "ok");
  assert.equal(detector.check("read", { limit: 10, path: "/a.txt" }).level, "warning");
  assert.equal(detector.check("read", { path: "/b.txt", limit: 10 }).level, "none");
});

test("trims the window to historySize", () => {
  const detector = new ToolLoopDetector({ historySize: 3, warningThreshold: 3, criticalThreshold: 4 });
  for (let index = 0; index < 5; index += 1) {
    detector.record("read", { path: `/f${index}.txt` }, "ok");
  }
  assert.equal(detector.size, 3);
  assert.equal(detector.check("read", { path: "/f0.txt" }).level, "none");
});

test("warns on repeated identical output", () => {
  const detector = new ToolLoopDetector({
    repeatedResultThreshold: 3,
    warningThreshold: 99,
    criticalThreshold: 99,
  });
  detector.record("bash", { command: "a" }, "same");
  detector.record("bash", { command: "b" }, "same");
  const result = detector.record("bash", { command: "c" }, "same");
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "repeated_result");
  assert.equal(result.repeatCount, 3);
});

test("warns when arguments only differ by a regenerated timestamp", () => {
  const detector = new ToolLoopDetector();
  for (let index = 0; index < 5; index += 1) {
    detector.record(
      "bash",
      { command: `curl -H "x-ts: 2026-09-12T10:00:0${index}Z" https://example.com/api` },
      "chunk",
    );
  }
  const result = detector.check("bash", {
    command: 'curl -H "x-ts: 2026-09-12T10:00:09Z" https://example.com/api',
  });
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "near_identical_call");
});

test("near-identical repeats never abort", () => {
  const detector = new ToolLoopDetector({ nearDuplicateThreshold: 3, criticalThreshold: 3 });
  for (let index = 0; index < 8; index += 1) {
    const result = detector.record(
      "bash",
      { command: `curl -H "x-ts: 2026-09-12T10:00:0${index}Z" https://example.com/api` },
      `run-${index}`,
    );
    assert.notEqual(result.level, "critical");
  }
  const result = detector.check("bash", {
    command: 'curl -H "x-ts: 2026-09-12T10:00:09Z" https://example.com/api',
  });
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "near_identical_call");
  assert.equal(result.detail, "bash × 9");
});

test("warns when two tools ping-pong", () => {
  const detector = new ToolLoopDetector();
  for (let round = 0; round < 4; round += 1) {
    detector.record("read", { path: "/a.txt" }, "ok");
    detector.record("write", { path: "/a.txt", content: "x" }, "ok");
  }
  const result = detector.check("read", { path: "/a.txt" });
  assert.equal(result.level, "warning");
  assert.equal(result.reason, "cyclic_calls");
  assert.equal(result.repeatCount, 4);
  assert.equal(result.detail, "read → write × 4");
});

test("a short ping-pong stays quiet", () => {
  const detector = new ToolLoopDetector();
  for (let round = 0; round < 2; round += 1) {
    detector.record("read", { path: "/a.txt" }, "ok");
    detector.record("write", { path: "/a.txt", content: "x" }, "ok");
  }
  assert.equal(detector.check("read", { path: "/a.txt" }).level, "none");
});

test("different paths are not treated as near-duplicates", () => {
  const detector = new ToolLoopDetector();
  for (let index = 0; index < 8; index += 1) {
    detector.record("read", { path: `/notes/file-${index}.md` }, "ok");
  }
  assert.equal(detector.check("read", { path: "/notes/file-9.md" }).level, "none");
});

test("advancing page numbers or offsets are not near-duplicates", () => {
  const detector = new ToolLoopDetector();
  for (let page = 0; page < 8; page += 1) {
    detector.record("read", { path: "/big.log", offset: page * 1000, limit: 1000 }, "chunk");
  }
  assert.equal(detector.check("read", { path: "/big.log", offset: 8000, limit: 1000 }).level, "none");
});
