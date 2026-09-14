export type ModelInputModality = "text" | "image";

/**
 * Which modalities a model accepts. The kernel drops every image part of a
 * request unless the model declares `"image"` here, so this single list decides
 * whether a screenshot or a photo reaches the API or arrives as a written note
 * saying the model cannot see pictures.
 *
 * The Pi catalog ships its own answer, but the models people actually configure
 * move faster than the catalog, so Qing lets the user override it per provider.
 * The override can only add image support to a built-in provider: a model the
 * catalog already ships as vision capable must never be downgraded by a stale
 * local flag.
 */
export function resolveBuiltInModelInput(
  supportsImageInput: boolean | undefined,
  catalogInput: ModelInputModality[],
): ModelInputModality[] {
  return supportsImageInput === true ? ["text", "image"] : catalogInput;
}

/**
 * Custom OpenAI-compatible endpoints were always advertised as image capable,
 * so that stays the default and the flag is what turns it off for a text only
 * model.
 */
export function resolveCustomModelInput(
  supportsImageInput: boolean | undefined,
): ModelInputModality[] {
  return supportsImageInput === false ? ["text"] : ["text", "image"];
}
