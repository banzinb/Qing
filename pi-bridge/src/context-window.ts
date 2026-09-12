import { getBuiltinModel, getBuiltinProviders, type BuiltinProvider } from "@earendil-works/pi-ai/providers/all";

/**
 * What Aether used to assume for every model. Kept only as the last resort,
 * for custom OpenAI-compatible endpoints the Pi catalog never heard of.
 */
export const FALLBACK_CONTEXT_WINDOW = 128_000;

export interface ContextWindowInput {
  pi_provider_id: string;
  model_id: string;
  /** The window the caller sent, if any. Zero or missing means "not specified". */
  context_window?: number;
}

/**
 * The context window the agent should plan against.
 *
 * Order matters: an explicit number from the caller wins, then the Pi model
 * catalog (which knows DeepSeek V4 is 1M and Claude is 200K), then the old
 * 128K default. Nothing here touches the network: the catalog ships with the
 * kernel, so a model that is unknown to it just gets the safe fallback rather
 * than a wrong-but-confident number.
 */
export function resolveContextWindow(input: ContextWindowInput): number {
  const explicit = Math.floor(Number(input.context_window ?? 0));
  if (Number.isFinite(explicit) && explicit > 0) return explicit;

  const providerId = input.pi_provider_id.trim();
  const modelId = input.model_id.trim();
  if (!providerId || !modelId) return FALLBACK_CONTEXT_WINDOW;
  if (!getBuiltinProviders().includes(providerId as BuiltinProvider)) {
    return FALLBACK_CONTEXT_WINDOW;
  }

  try {
    const model = getBuiltinModel(providerId as BuiltinProvider, modelId as never);
    const known = Math.floor(Number((model as { contextWindow?: number } | undefined)?.contextWindow ?? 0));
    if (Number.isFinite(known) && known > 0) return known;
  } catch {
    // Known provider, unknown model id: fall through to the fallback.
  }
  return FALLBACK_CONTEXT_WINDOW;
}
