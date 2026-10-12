/** Exponential backoff with jitter: 1s, 2s, 4s … capped at 15s. */
export function backoffDelay(attempt: number): number {
  const base = Math.min(15_000, 1_000 * 2 ** attempt);
  return base / 2 + Math.random() * (base / 2);
}
