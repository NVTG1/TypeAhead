import React, { useEffect, useState, useCallback } from "react";
import { api } from "../api";

export default function TrendingPanel({ enhanced, refreshKey }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.trending(enhanced, 10);
      setItems(res.trending || []);
      setError(null);
    } catch (e) {
      setError("Couldn't load trending searches.");
    } finally {
      setLoading(false);
    }
  }, [enhanced]);

  useEffect(() => {
    load();
  }, [load, refreshKey]);

  return (
    <div className="panel">
      <div className="panel-header">
        <span className="panel-title">Trending</span>
        <span className="panel-badge mono">{enhanced ? "recency-aware" : "all-time"}</span>
      </div>
      {loading && <div className="panel-loading mono">loading…</div>}
      {error && <div className="search-error">{error}</div>}
      {!loading && !error && (
        <ol className="trending-list">
          {items.map((item, i) => (
            <li key={item.query} className="trending-item">
              <span className="trending-rank mono">{String(i + 1).padStart(2, "0")}</span>
              <span className="trending-query">{item.query}</span>
              <span className="trending-count mono">{Math.round(item.score).toLocaleString()}</span>
            </li>
          ))}
          {items.length === 0 && <div className="panel-loading mono">no data yet</div>}
        </ol>
      )}
    </div>
  );
}
