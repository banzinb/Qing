/**
 * Tool approval policy (Qing v2.3).
 *
 * Everything the agent can do reaches the host through exactly two functions:
 * `requestRuntimeOperation` (shell plus file access) and `requestAgentHostTool`
 * (the `aether_*` tools). Both consult this module, so one small policy covers
 * the whole surface without touching the Pi runtime.
 *
 * The classifier stays deliberately narrow: it answers "would a reasonable
 * person want to be asked before this happens?", nothing more. It returns facts
 * rather than prose, because the wording shown to the user lives in the app's
 * string resources and has to be localizable.
 */

export type ApprovalMode = "off" | "relaxed" | "balanced" | "strict";

const APPROVAL_MODES: readonly ApprovalMode[] = ["off", "relaxed", "balanced", "strict"];

export type ToolApprovalDecision = "approved" | "approved_for_session" | "denied" | "timed_out";

const TOOL_APPROVAL_DECISIONS: readonly ToolApprovalDecision[] = [
  "approved",
  "approved_for_session",
  "denied",
  "timed_out",
];

/**
 * Anything unrecognized counts as a refusal, so a garbled answer never runs a
 * tool by accident.
 */
export function normalizeToolApprovalDecision(value: unknown): ToolApprovalDecision {
  const raw = typeof value === "string" ? value.trim().toLowerCase() : "";
  return TOOL_APPROVAL_DECISIONS.includes(raw as ToolApprovalDecision)
    ? (raw as ToolApprovalDecision)
    : "denied";
}

/**
 * There are two ways to say yes. "approved_for_session" also remembers the
 * decision for the rest of the session; treating it as anything other than a
 * yes makes the run it answered for fail while the memory silently sticks,
 * which reads to the user as "it refused, then did it anyway".
 */
export function isApprovalGranted(decision: ToolApprovalDecision): boolean {
  return decision === "approved" || decision === "approved_for_session";
}

export function normalizeApprovalMode(raw: unknown): ApprovalMode {
  const value = typeof raw === "string" ? raw.trim().toLowerCase() : "";
  return APPROVAL_MODES.includes(value as ApprovalMode) ? (value as ApprovalMode) : "balanced";
}

export type ApprovalSubject =
  | { kind: "runtime"; name: string; detail: string }
  | { kind: "host_tool"; name: string; detail: string; argumentsJson: string };

export interface ApprovalRequirement {
  /** Whether the app should ask the user before this runs. */
  required: boolean;
  /** Stable identity for "always allow for this session". */
  scopeKey: string;
  /** Short, human-scannable summary of what is about to happen. */
  preview: string;
  /** Raw tool arguments, forwarded so the prompt can show the details. */
  argumentsJson: string;
}

const EXEC_RUNTIME_OPS = new Set(["bash"]);
const WRITE_RUNTIME_OPS = new Set(["writeFile", "write", "delete", "remove", "move"]);
/**
 * Benign runtime work: reads, plus mkdir, which only ever precedes a write that
 * is itself gated. Balanced stays quiet for these.
 */
const BENIGN_RUNTIME_OPS = new Set([
  "access",
  "readFile",
  "read",
  "detectMime",
  "stat",
  "list",
  "mkdir",
]);

/**
 * Host tools with consequences the user would want to see first: they drive the
 * screen or browser, install things, or change how the app itself behaves.
 */
const SENSITIVE_HOST_TOOLS = new Set([
  "browser",
  "agent_display",
  "aether_agent_mode_manage",
  "aether_config_set",
  "aether_developer_manage",
  "aether_device_manage",
  "aether_extension_manage",
  "aether_runtime_manage",
  "aether_scheduled_task_manage",
  "aether_skill_manage",
  "aether_termux_manage",
]);

/**
 * Host tools that carry state into the system but are usually what the user
 * just asked for. Balanced lets them through; strict still asks.
 */
const MILD_HOST_TOOLS = new Set([
  "memory_write",
  "fetch_web_url",
  "web_search",
  "tavily_search",
]);

/**
 * Read-only host tools. Exempt in every mode including strict, otherwise
 * "strict" would mean approving the act of looking something up.
 */
const HARMLESS_HOST_TOOLS = new Set(["aether_config_get", "memory_query"]);

/**
 * Device actions with no lasting effect. Reading the weather or reading text
 * aloud does not change the phone, so balanced does not interrupt for them.
 * Anything not listed here (opening an app, writing the clipboard) does.
 */
const HARMLESS_DEVICE_ACTIONS = new Set([
  "device_info",
  "weather",
  "clipboard_get",
  "speak",
  "stop_media",
  "location_get",
  "contacts_search",
  "calendar_read",
  "photos_recent",
]);

/** Relaxed mode only stops at these: the ones that can reshape the install. */
const SYSTEM_LEVEL_HOST_TOOLS = new Set([
  "aether_config_set",
  "aether_developer_manage",
  "aether_extension_manage",
  "aether_runtime_manage",
]);

const PREVIEW_LIMIT = 400;
const SCOPED_LIMIT = 160;

export function approvalRequirement(
  subject: ApprovalSubject,
  mode: ApprovalMode,
): ApprovalRequirement {
  const scopeKey = scopeKeyFor(subject);
  const preview = previewFor(subject);
  const argumentsJson = subject.kind === "host_tool" ? subject.argumentsJson : "";
  if (mode === "off" || isHarmless(subject)) {
    return { required: false, scopeKey, preview, argumentsJson };
  }
  const required = mode === "strict"
    ? true
    : mode === "relaxed"
      ? relaxedRequires(subject)
      : balancedRequires(subject);
  return { required, scopeKey, preview, argumentsJson };
}

function isHarmless(subject: ApprovalSubject): boolean {
  if (subject.kind === "runtime") return false;
  if (HARMLESS_HOST_TOOLS.has(subject.name)) return true;
  if (subject.name !== "aether_device_manage") return false;
  const action = deviceActionOf(subject.argumentsJson);
  return action !== "" && HARMLESS_DEVICE_ACTIONS.has(action);
}

function relaxedRequires(subject: ApprovalSubject): boolean {
  if (subject.kind === "runtime") return EXEC_RUNTIME_OPS.has(subject.name);
  return SYSTEM_LEVEL_HOST_TOOLS.has(subject.name);
}

function balancedRequires(subject: ApprovalSubject): boolean {
  if (subject.kind === "runtime") {
    if (BENIGN_RUNTIME_OPS.has(subject.name)) return false;
    return EXEC_RUNTIME_OPS.has(subject.name) || WRITE_RUNTIME_OPS.has(subject.name);
  }
  if (SENSITIVE_HOST_TOOLS.has(subject.name)) return true;
  if (MILD_HOST_TOOLS.has(subject.name)) return false;
  // Unknown host tools are not in the reachable set today; if one shows up,
  // ask rather than assume it is safe.
  return true;
}

function scopeKeyFor(subject: ApprovalSubject): string {
  if (subject.kind === "runtime") return `runtime:${subject.name}`;
  if (subject.name === "aether_device_manage") {
    const action = deviceActionOf(subject.argumentsJson);
    if (action !== "") return `host_tool:${subject.name}:${action}`;
  }
  return `host_tool:${subject.name}`;
}

function previewFor(subject: ApprovalSubject): string {
  if (subject.kind === "runtime") return truncate(subject.detail, PREVIEW_LIMIT);
  const action = deviceActionOf(subject.argumentsJson);
  const target = firstStringArgument(subject.argumentsJson, [
    "target",
    "value",
    "url",
    "path",
    "query",
    "text",
    "command",
    "package_name",
    "title",
    "start",
    "message",
  ]);
  if (action && target) return `${subject.name} ${action} · ${truncate(target, SCOPED_LIMIT)}`;
  if (action) return `${subject.name} ${action}`;
  const detail = subject.detail.trim();
  if (detail) return `${subject.name} · ${truncate(detail, PREVIEW_LIMIT)}`;
  return subject.name;
}

function deviceActionOf(argumentsJson: string): string {
  const args = parseArguments(argumentsJson);
  return typeof args.action === "string" ? args.action.trim() : "";
}

function firstStringArgument(argumentsJson: string, keys: readonly string[]): string {
  const args = parseArguments(argumentsJson);
  for (const key of keys) {
    const value = args[key];
    if (typeof value === "string" && value.trim()) return value.trim();
  }
  return "";
}

function parseArguments(argumentsJson: string): Record<string, unknown> {
  if (!argumentsJson.trim()) return {};
  try {
    const parsed = JSON.parse(argumentsJson);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function truncate(text: string, limit: number): string {
  const trimmed = text.trim();
  return trimmed.length > limit ? `${trimmed.slice(0, limit)}…` : trimmed;
}
