const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

async function request(path, options = {}) {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    throw new Error(`Request failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

export const api = {
  suggest: (prefix, enhanced) =>
    request(`/suggest?q=${encodeURIComponent(prefix)}&enhanced=${enhanced}`),

  trending: (enhanced = true, limit = 10) =>
    request(`/trending?enhanced=${enhanced}&limit=${limit}`),

  search: (query) =>
    request(`/search`, {
      method: "POST",
      body: JSON.stringify({ query }),
    }),

  cacheDebug: (prefix) => request(`/cache/debug?prefix=${encodeURIComponent(prefix)}`),

  batchStats: () => request(`/admin/batch-stats`),
};
