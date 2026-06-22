import React from "react";

const NODES = ["redis-node-1", "redis-node-2", "redis-node-3"];

/**
 * Visualizes the 3 physical Redis cache nodes as points on a ring, with
 * the node that served the most recent request pulsing. This is the
 * literal payoff of consistent hashing made visible: the same prefix
 * always lights up the same node, and you can watch routing happen live
 * as you type different prefixes.
 */
export default function CacheRingViz({ activeNode, hit }) {
  const size = 132;
  const center = size / 2;
  const radius = 46;

  return (
    <div className="cache-ring">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle
          cx={center}
          cy={center}
          r={radius}
          fill="none"
          stroke="var(--border)"
          strokeWidth="1"
          strokeDasharray="3 4"
        />
        {NODES.map((node, i) => {
          const angle = (i / NODES.length) * 2 * Math.PI - Math.PI / 2;
          const x = center + radius * Math.cos(angle);
          const y = center + radius * Math.sin(angle);
          const isActive = node === activeNode;
          return (
            <g key={node}>
              {isActive && (
                <circle
                  cx={x}
                  cy={y}
                  r="13"
                  fill="none"
                  stroke={hit ? "var(--live)" : "var(--accent)"}
                  strokeWidth="1.5"
                  opacity="0.5"
                  className="ring-pulse"
                />
              )}
              <circle
                cx={x}
                cy={y}
                r="8"
                fill={isActive ? (hit ? "var(--live)" : "var(--accent)") : "var(--bg-raised-2)"}
                stroke={isActive ? "transparent" : "var(--border-bright)"}
                strokeWidth="1"
              />
            </g>
          );
        })}
        <circle cx={center} cy={center} r="2" fill="var(--text-faint)" />
      </svg>
      <div className="cache-ring-label mono">
        {activeNode ? (
          <>
            <span style={{ color: hit ? "var(--live)" : "var(--accent)" }}>
              {hit ? "HIT" : "MISS"}
            </span>{" "}
            <span style={{ color: "var(--text-dim)" }}>{activeNode}</span>
          </>
        ) : (
          <span style={{ color: "var(--text-faint)" }}>awaiting query…</span>
        )}
      </div>
    </div>
  );
}
