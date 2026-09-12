import { createHash } from "node:crypto";

/**
 * Aether V2.4 tool-loop guardrail.
 *
 * The agent loop lives in the Pi TypeScript session, so loop detection sits
 * here rather than in Kotlin. This module is pure logic: it keeps a
 * per-session sliding window of finished tool calls and answers "is the next
 * call the same thing again?".
 *
 * Four signals are tracked:
 *   - identical call       (same tool name + same arguments)      -> warning, then critical
 *   - near-identical call  (arguments differ only by numbers/ids) -> warning only
 *   - cyclic call sequence (a 2..4 call pattern repeating)        -> warning only
 *   - identical result     (same tool name + same output)         -> warning only
 *
 * Only "identical call, back to back" escalates to critical: that is the
 * signature of a stuck agent, while the other three can be legitimate (paging
 * through a file, polling a status, alternating read/edit). The caller decides
 * what to do with a level: surface a status card for a warning, abort the
 * session for a critical.
 */

export type LoopLevel = "none" | "warning" | "critical";

/** Why the detector fired. English, for diagnostics and future UI mapping. */
export type LoopReason =
  | "none"
  | "identical_call"
  | "near_identical_call"
  | "cyclic_calls"
  | "repeated_result";

export interface LoopCheckResult {
  level: LoopLevel;
  reason: LoopReason;
  /** Matching entries inside the window, or cycle rounds for "cyclic_calls". */
  repeatCount: number;
  /** Short human-readable reason; empty when level is "none". */
  message: string;
  /** Locale-neutral subtitle for the status card, e.g. "bash × 7". Empty when level is "none". */
  detail: string;
}

export interface ToolLoopConfig {
  /** Sliding-window size in tool calls. */
  historySize: number;
  /** Identical call at/above this count inside the window -> warning. 0 disables. */
  warningThreshold: number;
  /** Identical call at/above this count *in a row* -> critical (abort). 0 disables. */
  criticalThreshold: number;
  /** Near-identical call at/above this count inside the window -> warning. 0 disables. */
  nearDuplicateThreshold: number;
  /** Identical result at/above this count inside the window -> warning. 0 disables. */
  repeatedResultThreshold: number;
  /** A 2..maxCycleLength call pattern repeating this many rounds -> warning. 0 disables. */
  cycleRepeatThreshold: number;
  /** Longest call pattern considered a cycle. */
  maxCycleLength: number;
}

export const DEFAULT_TOOL_LOOP_CONFIG: ToolLoopConfig = {
  historySize: 30,
  warningThreshold: 6,
  criticalThreshold: 12,
  nearDuplicateThreshold: 6,
  repeatedResultThreshold: 15,
  cycleRepeatThreshold: 4,
  maxCycleLength: 4,
};

interface ToolCallRecord {
  toolName: string;
  /** toolName + exact argument hash. */
  argsKey: string;
  /** toolName + normalized argument hash. */
  nearKey: string;
  resultHash: string;
}

const NO_LOOP: LoopCheckResult = Object.freeze({
  level: "none",
  reason: "none",
  repeatCount: 0,
  message: "",
  detail: "",
});

function stableSerialize(value: unknown): string {
  if (value === undefined) return "undefined";
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null";
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(",")}]`;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, entryValue]) => entryValue !== undefined)
    .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0));
  return `{${entries
    .map(([key, entryValue]) => `${JSON.stringify(key)}:${stableSerialize(entryValue)}`)
    .join(",")}}`;
}

const TIMESTAMP_PATTERN =
  /\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?)?/g;
const UUID_PATTERN = /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g;
const LONG_HEX_PATTERN = /\b[0-9a-fA-F]{16,}\b/g;
const EPOCH_PATTERN = /\b\d{10,}\b/g;
/** Epoch seconds/millis and similar counters are volatile; page numbers and offsets are not. */
const MIN_VOLATILE_NUMBER = 1_000_000_000;

/**
 * Collapses "the same call with a different number/identifier in it" onto one
 * shape, so a regenerated timestamp or request id stops making two otherwise
 * identical calls look different.
 *
 * Deliberately conservative: only flags that are volatile by nature (ISO
 * timestamps, UUIDs, long hex, 10+ digit counters) are erased. Plain small
 * numbers are kept, because a call whose page number or byte offset advances is
 * usually making progress, not looping. That trade-off costs us the "page 1,
 * page 2, ..." shape and buys us not warning on batch reads of numbered files.
 */
function normalizeValue(value: unknown): unknown {
  if (typeof value === "number") {
    return Math.abs(value) >= MIN_VOLATILE_NUMBER ? "#" : value;
  }
  if (typeof value === "string") {
    return value
      .replace(TIMESTAMP_PATTERN, "@time")
      .replace(UUID_PATTERN, "@id")
      .replace(LONG_HEX_PATTERN, "@id")
      .replace(EPOCH_PATTERN, "@time");
  }
  if (value === null || value === undefined || typeof value !== "object") return value;
  if (Array.isArray(value)) return value.map(normalizeValue);
  const normalized: Record<string, unknown> = {};
  for (const key of Object.keys(value as Record<string, unknown>).sort()) {
    normalized[key] = normalizeValue((value as Record<string, unknown>)[key]);
  }
  return normalized;
}

function hashValue(value: unknown): string {
  return createHash("sha256").update(stableSerialize(value)).digest("hex").slice(0, 16);
}

function hashNormalized(value: unknown): string {
  return createHash("sha256").update(stableSerialize(normalizeValue(value))).digest("hex").slice(0, 16);
}

interface CycleMatch {
  length: number;
  rounds: number;
  label: string;
}

export class ToolLoopDetector {
  private readonly config: ToolLoopConfig;
  private readonly records: ToolCallRecord[] = [];

  constructor(config: Partial<ToolLoopConfig> = {}) {
    this.config = { ...DEFAULT_TOOL_LOOP_CONFIG, ...config };
  }

  /** Number of calls currently held in the window. */
  get size(): number {
    return this.records.length;
  }

  /** Inspect a pending call against the window. Does not mutate state. */
  check(toolName: string, args: unknown): LoopCheckResult {
    const argsKey = `${toolName}:${hashValue(args)}`;

    const consecutive = this.countTrailing(argsKey) + 1;
    if (this.config.criticalThreshold > 0 && consecutive >= this.config.criticalThreshold) {
      return {
        level: "critical",
        reason: "identical_call",
        repeatCount: consecutive,
        message: `Tool "${toolName}" called ${consecutive} times in a row with identical arguments.`,
        detail: `${toolName} × ${consecutive}`,
      };
    }

    if (this.config.warningThreshold > 0) {
      const identical = this.countMatching((record) => record.argsKey === argsKey) + 1;
      if (identical >= this.config.warningThreshold) {
        return {
          level: "warning",
          reason: "identical_call",
          repeatCount: identical,
          message: `Tool "${toolName}" called ${identical} times with identical arguments.`,
          detail: `${toolName} × ${identical}`,
        };
      }
    }

    if (this.config.nearDuplicateThreshold > 0) {
      const nearKey = `${toolName}:${hashNormalized(args)}`;
      const near = this.countMatching((record) => record.nearKey === nearKey) + 1;
      if (near >= this.config.nearDuplicateThreshold) {
        return {
          level: "warning",
          reason: "near_identical_call",
          repeatCount: near,
          message: `Tool "${toolName}" called ${near} times with near-identical arguments.`,
          detail: `${toolName} × ${near}`,
        };
      }
    }

    if (this.config.cycleRepeatThreshold > 0) {
      const cycle = this.detectCycle(argsKey);
      if (cycle) {
        return {
          level: "warning",
          reason: "cyclic_calls",
          repeatCount: cycle.rounds,
          message: `Tool calls are repeating a ${cycle.length}-call cycle for ${cycle.rounds} rounds.`,
          detail: `${cycle.label} × ${cycle.rounds}`,
        };
      }
    }

    return NO_LOOP;
  }

  /**
   * Record a finished call. Returns a warning when the same tool keeps
   * producing byte-identical output, which usually means a retry loop that
   * argument matching alone would miss. Never returns "critical": identical
   * output can be legitimate (empty listings, stable reads).
   */
  record(toolName: string, args: unknown, result: unknown): LoopCheckResult {
    const resultHash = hashValue(result);
    this.records.push({
      toolName,
      argsKey: `${toolName}:${hashValue(args)}`,
      nearKey: `${toolName}:${hashNormalized(args)}`,
      resultHash,
    });
    if (this.records.length > this.config.historySize) {
      this.records.splice(0, this.records.length - this.config.historySize);
    }
    if (this.config.repeatedResultThreshold <= 0) return NO_LOOP;
    const identicalResults = this.countMatching(
      (record) => record.toolName === toolName && record.resultHash === resultHash,
    );
    if (identicalResults >= this.config.repeatedResultThreshold) {
      return {
        level: "warning",
        reason: "repeated_result",
        repeatCount: identicalResults,
        message: `Tool "${toolName}" returned identical output ${identicalResults} times.`,
        detail: `${toolName} × ${identicalResults}`,
      };
    }
    return NO_LOOP;
  }

  reset(): void {
    this.records.length = 0;
  }

  private countMatching(predicate: (record: ToolCallRecord) => boolean): number {
    let count = 0;
    for (const record of this.records) {
      if (predicate(record)) count += 1;
    }
    return count;
  }

  /** How many of the most recent finished calls match this key, back to back. */
  private countTrailing(key: string): number {
    let count = 0;
    for (let index = this.records.length - 1; index >= 0; index -= 1) {
      if (this.records[index].argsKey !== key) break;
      count += 1;
    }
    return count;
  }

  /**
   * Looks for the shortest period in 2..maxCycleLength that the tail of the
   * window keeps repeating, including the pending call. Catches A→B→A→B, which
   * per-call counting alone never sees because no single call repeats often
   * enough inside the window.
   */
  private detectCycle(pendingKey: string): CycleMatch | null {
    const keys = this.records.map((record) => record.argsKey);
    keys.push(pendingKey);
    const longest = Math.max(2, this.config.maxCycleLength);
    for (let length = 2; length <= longest; length += 1) {
      const minimumRounds = this.config.cycleRepeatThreshold;
      if (keys.length < length * minimumRounds) continue;
      // Walk back over every element that keeps the period, so the label reads
      // in call order from where the repeating run actually starts.
      let start = keys.length - length;
      while (start > 0 && keys[start - 1] === keys[start - 1 + length]) {
        start -= 1;
      }
      const rounds = Math.floor((keys.length - start) / length);
      if (rounds >= minimumRounds) {
        const tools = keys
          .slice(start, start + length)
          .map((key) => key.slice(0, key.lastIndexOf(":")));
        return { length, rounds, label: tools.join(" → ") };
      }
    }
    return null;
  }
}
