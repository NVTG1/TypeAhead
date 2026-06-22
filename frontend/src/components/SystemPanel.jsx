import React, { useEffect, useState } from "react";
import CacheRingViz from "./CacheRingViz";
import { api } from "../api";

export default function SystemPanel({ suggestMeta, refreshKey }) {
  const [batchStats, setBatchStats] = useState(null);

  useEffect(() => {
    const tick = async () => {
      try {
        const stats = await api.batchStats();
        setBatchStats(stats);
      } catch (e) {
        // silent - this is a secondary readout
      }
    };
    tick();
    const interval = setInterval(tick, 3000);
    return () => clearInterval(interval);
  }, [refreshKey]);

  return (
    <div className="panel">
      <div className="panel-header">
        <span className="panel-title">Cache routing</span>
        <span className="panel-badge mono">consistent hash · 3 nodes</span>
      </div>

      <CacheRingViz activeNode={suggestMeta?.cacheNode} hit={suggestMeta?.cacheHit} />

      <div className="metric-row">
        <span className="metric-label">latency</span>
        <span className="metric-value mono">
          {suggestMeta ? `${suggestMeta.latencyMs.toFixed(1)} ms` : "—"}
        </span>
      </div>
      <div className="metric-row">
        <span className="metric-label">ranking</span>
        <span className="metric-value mono">
          {suggestMeta ? (suggestMeta.rankingEnhanced ? "enhanced" : "basic") : "—"}
        </span>
      </div>

      <div className="panel-divider" />

      <div className="panel-header">
        <span className="panel-title">Batch writes</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">requests received</span>
        <span className="metric-value mono">{batchStats?.totalSearchRequests ?? "—"}</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">db writes performed</span>
        <span className="metric-value mono">{batchStats?.totalDbWrites ?? "—"}</span>
      </div>
      <div className="metric-row">
        <span className="metric-label">write reduction</span>
        <span className="metric-value mono" style={{ color: "var(--live)" }}>
          {batchStats?.writeReductionRatio ? `${batchStats.writeReductionRatio.toFixed(1)}×` : "—"}
        </span>
      </div>
      <div className="metric-row">
        <span className="metric-label">buffer (pending)</span>
        <span className="metric-value mono">{batchStats?.currentBufferSize ?? "—"}</span>
      </div>
    </div>
  );
}
