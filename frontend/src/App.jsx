import React, { useState } from "react";
import SearchBox from "./components/SearchBox";
import TrendingPanel from "./components/TrendingPanel";
import SystemPanel from "./components/SystemPanel";
import "./App.css";

export default function App() {
  const [enhanced, setEnhanced] = useState(true);
  const [suggestMeta, setSuggestMeta] = useState(null);
  const [refreshKey, setRefreshKey] = useState(0);

  const handleSearched = () => {
    setRefreshKey((k) => k + 1);
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-title">
          <span className="app-title-mark mono">/suggest</span>
          <h1>Search Typeahead</h1>
        </div>
        <p className="app-subtitle">
          AOL query log · Postgres + 3-node Redis cache · consistent hashing · batched writes
        </p>
      </header>

      <main className="app-main">
        <section className="search-section">
          <div className="ranking-toggle">
            <button
              className={!enhanced ? "active" : ""}
              onClick={() => setEnhanced(false)}
            >
              All-time popularity
            </button>
            <button
              className={enhanced ? "active" : ""}
              onClick={() => setEnhanced(true)}
            >
              Recency-aware
            </button>
          </div>

          <SearchBox
            enhanced={enhanced}
            onSuggestMeta={setSuggestMeta}
            onSearched={handleSearched}
          />

          <TrendingPanel enhanced={enhanced} refreshKey={refreshKey} />
        </section>

        <aside className="system-section">
          <SystemPanel suggestMeta={suggestMeta} refreshKey={refreshKey} />
        </aside>
      </main>

      <footer className="app-footer mono">
        HLD101 · search-typeahead · query data sourced from a public AOL search log
      </footer>
    </div>
  );
}
