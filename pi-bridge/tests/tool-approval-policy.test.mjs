import assert from "node:assert/strict";
import { test } from "node:test";
import { createJiti } from "jiti";

const jiti = createJiti(import.meta.url);
const {
  approvalRequirement,
  isApprovalGranted,
  normalizeApprovalMode,
  normalizeToolApprovalDecision,
} = await jiti.import("../src/tool-approval-policy.ts");

const bash = (command) => ({ kind: "runtime", name: "bash", detail: command });
const runtime = (name, detail = "") => ({ kind: "runtime", name, detail });

// The phone tool was renamed during the de-branding pass; sessions stored
// before that still call it by its old name, so both have to answer the same.
const DEVICE_TOOL = "qing_device_manage";
const LEGACY_DEVICE_TOOL = "aether_device_manage";

const hostTool = (name, args = {}) => ({
  kind: "host_tool",
  name,
  detail: "",
  argumentsJson: JSON.stringify(args),
});

test("off mode never asks", () => {
  for (const subject of [bash("rm -rf /"), runtime("writeFile"), hostTool("browser")]) {
    assert.equal(approvalRequirement(subject, "off").required, false);
  }
});

test("balanced asks before shell and file writes", () => {
  assert.equal(approvalRequirement(bash("ls"), "balanced").required, true);
  assert.equal(approvalRequirement(runtime("writeFile", "/a.txt"), "balanced").required, true);
});

test("balanced stays quiet for reads and directory setup", () => {
  for (const name of ["access", "readFile", "detectMime", "mkdir"]) {
    assert.equal(approvalRequirement(runtime(name), "balanced").required, false, name);
  }
});

test("balanced gates device actions that change something", () => {
  assert.equal(
    approvalRequirement(hostTool(DEVICE_TOOL, { action: "open", target: "weixin" }), "balanced")
      .required,
    true,
  );
  assert.equal(
    approvalRequirement(
      hostTool(DEVICE_TOOL, { action: "clipboard_set", text: "hi" }),
      "balanced",
    ).required,
    true,
  );
  for (const action of ["calendar_add", "alarm_set", "timer_set", "photo_export", "contacts_add"]) {
    assert.equal(
      approvalRequirement(hostTool(DEVICE_TOOL, { action }), "balanced").required,
      true,
      action,
    );
  }
});

test("balanced lets the harmless device actions through", () => {
  for (const action of [
    "device_info",
    "weather",
    "clipboard_get",
    "speak",
    "stop_media",
    "location_get",
    "contacts_search",
    "calendar_read",
    "photos_recent",
  ]) {
    assert.equal(
      approvalRequirement(hostTool(DEVICE_TOOL, { action }), "balanced").required,
      false,
      action,
    );
  }
});

test("balanced gates the tools that reshape the app or drive the screen", () => {
  for (const name of ["browser", "agent_display", "aether_extension_manage", "aether_config_set"]) {
    assert.equal(approvalRequirement(hostTool(name), "balanced").required, true, name);
  }
});

test("balanced allows lookups and recorded memories", () => {
  for (const name of ["aether_config_get", "memory_query", "memory_write", "web_search"]) {
    assert.equal(approvalRequirement(hostTool(name), "balanced").required, false, name);
  }
});

test("an unknown host tool is treated as unsafe rather than waved through", () => {
  assert.equal(approvalRequirement(hostTool("aether_mystery_manage"), "balanced").required, true);
});

test("relaxed only stops at shell and system-level tools", () => {
  assert.equal(approvalRequirement(bash("ls"), "relaxed").required, true);
  assert.equal(approvalRequirement(runtime("writeFile"), "relaxed").required, false);
  assert.equal(approvalRequirement(hostTool("aether_extension_manage"), "relaxed").required, true);
  assert.equal(approvalRequirement(hostTool("agent_display"), "relaxed").required, false);
  assert.equal(
    approvalRequirement(hostTool(DEVICE_TOOL, { action: "open" }), "relaxed").required,
    false,
  );
});

test("strict asks for reads too but never for a lookup", () => {
  assert.equal(approvalRequirement(runtime("readFile"), "strict").required, true);
  assert.equal(approvalRequirement(hostTool("memory_write"), "strict").required, true);
  assert.equal(approvalRequirement(hostTool("aether_config_get"), "strict").required, false);
  assert.equal(approvalRequirement(hostTool("memory_query"), "strict").required, false);
});

test("scope keys separate what the user agrees to", () => {
  assert.equal(approvalRequirement(bash("ls"), "balanced").scopeKey, "runtime:bash");
  assert.equal(
    approvalRequirement(hostTool(DEVICE_TOOL, { action: "open" }), "balanced").scopeKey,
    "host_tool:qing_device_manage:open",
  );
  assert.equal(
    approvalRequirement(hostTool(DEVICE_TOOL, { action: "clipboard_set" }), "balanced")
      .scopeKey,
    "host_tool:qing_device_manage:clipboard_set",
  );
  assert.equal(
    approvalRequirement(hostTool("browser"), "balanced").scopeKey,
    "host_tool:browser",
  );
});

test("the preview shows what is about to happen", () => {
  assert.equal(approvalRequirement(bash("rm -rf /tmp/x"), "balanced").preview, "rm -rf /tmp/x");
  assert.equal(
    approvalRequirement(hostTool(DEVICE_TOOL, { action: "open", target: "weixin" }), "balanced")
      .preview,
    "qing_device_manage open · weixin",
  );
});

test("a very long command is clipped instead of flooding the prompt", () => {
  const preview = approvalRequirement(bash("x".repeat(900)), "balanced").preview;
  assert.ok(preview.length <= 401, `preview was ${preview.length} chars`);
  assert.ok(preview.endsWith("…"));
});

test("host tool arguments are forwarded so the prompt can show details", () => {
  const requirement = approvalRequirement(
    hostTool(DEVICE_TOOL, { action: "open", target: "weixin" }),
    "balanced",
  );
  assert.equal(JSON.parse(requirement.argumentsJson).target, "weixin");
});

test("the pre-rename device tool name still gets the same answers", () => {
  const open = { action: "open", target: "weixin" };
  assert.equal(
    approvalRequirement(hostTool(LEGACY_DEVICE_TOOL, open), "balanced").required,
    approvalRequirement(hostTool(DEVICE_TOOL, open), "balanced").required,
  );
  assert.equal(
    approvalRequirement(hostTool(LEGACY_DEVICE_TOOL, open), "balanced").scopeKey,
    "host_tool:aether_device_manage:open",
  );
  for (const action of ["device_info", "weather", "clipboard_get", "photos_recent"]) {
    assert.equal(
      approvalRequirement(hostTool(LEGACY_DEVICE_TOOL, { action }), "balanced").required,
      false,
      action,
    );
  }
  assert.equal(
    approvalRequirement(hostTool(LEGACY_DEVICE_TOOL, { action: "clipboard_set" }), "balanced")
      .required,
    true,
  );
});

test("approval mode parsing falls back to balanced", () => {
  assert.equal(normalizeApprovalMode("strict"), "strict");
  assert.equal(normalizeApprovalMode("  RELAXED "), "relaxed");
  assert.equal(normalizeApprovalMode("nonsense"), "balanced");
  assert.equal(normalizeApprovalMode(undefined), "balanced");
  assert.equal(normalizeApprovalMode(7), "balanced");
});

test("both ways of saying yes let the tool run", () => {
  assert.equal(isApprovalGranted("approved"), true);
  // Regression: this once fell through to the refusal branch, so the run the
  // user had just waved through failed while the session pass quietly stuck.
  assert.equal(isApprovalGranted("approved_for_session"), true);
});

test("a refusal or a timeout never runs the tool", () => {
  assert.equal(isApprovalGranted("denied"), false);
  assert.equal(isApprovalGranted("timed_out"), false);
});

test("an unrecognized decision is read as a refusal", () => {
  assert.equal(normalizeToolApprovalDecision("approved_for_session"), "approved_for_session");
  assert.equal(normalizeToolApprovalDecision("  APPROVED "), "approved");
  assert.equal(normalizeToolApprovalDecision("maybe"), "denied");
  assert.equal(normalizeToolApprovalDecision(undefined), "denied");
  assert.equal(normalizeToolApprovalDecision({ decision: "approved" }), "denied");
});
