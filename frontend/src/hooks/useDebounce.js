import { useEffect, useState } from "react";

/**
 * Debounces a fast-changing value (e.g. keystrokes in the search box) so
 * the UI only acts on it once typing pauses for `delayMs`. This is what
 * satisfies the assignment's "UI should avoid unnecessary backend calls"
 * requirement - without it, every keystroke would fire a /suggest call.
 */
export function useDebounce(value, delayMs = 200) {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
