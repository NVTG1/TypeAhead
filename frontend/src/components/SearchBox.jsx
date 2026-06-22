import React, { useState, useEffect, useRef, useCallback } from "react";
import { api } from "../api";
import { useDebounce } from "../hooks/useDebounce";

export default function SearchBox({ enhanced, onSuggestMeta, onSearched }) {
  const [value, setValue] = useState("");
  const [suggestions, setSuggestions] = useState([]);
  const [highlightIndex, setHighlightIndex] = useState(-1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [submitMsg, setSubmitMsg] = useState(null);
  const [open, setOpen] = useState(false);

  const debounced = useDebounce(value, 200);
  const inputRef = useRef(null);
  const requestIdRef = useRef(0);

  const fetchSuggestions = useCallback(
    async (prefix) => {
      if (!prefix || !prefix.trim()) {
        setSuggestions([]);
        setOpen(false);
        onSuggestMeta?.(null);
        return;
      }
      const thisRequestId = ++requestIdRef.current;
      setLoading(true);
      setError(null);
      try {
        const res = await api.suggest(prefix, enhanced);
        // Ignore stale responses that resolve out of order
        if (thisRequestId !== requestIdRef.current) return;
        setSuggestions(res.suggestions || []);
        setOpen(true);
        setHighlightIndex(-1);
        onSuggestMeta?.(res);
      } catch (e) {
        if (thisRequestId !== requestIdRef.current) return;
        setError("Couldn't reach the suggestions service.");
        setSuggestions([]);
      } finally {
        if (thisRequestId === requestIdRef.current) setLoading(false);
      }
    },
    [enhanced, onSuggestMeta]
  );

  useEffect(() => {
    fetchSuggestions(debounced);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced, enhanced]);

  const submitSearch = async (query) => {
    const q = (query ?? value).trim();
    if (!q) return;
    try {
      const res = await api.search(q);
      setSubmitMsg(res.message);
      setOpen(false);
      onSearched?.(q);
      setTimeout(() => setSubmitMsg(null), 2500);
    } catch (e) {
      setError("Search submission failed.");
    }
  };

  const handleKeyDown = (e) => {
    if (!open || suggestions.length === 0) {
      if (e.key === "Enter") submitSearch();
      return;
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setHighlightIndex((i) => Math.min(i + 1, suggestions.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setHighlightIndex((i) => Math.max(i - 1, -1));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (highlightIndex >= 0) {
        const chosen = suggestions[highlightIndex].query;
        setValue(chosen);
        submitSearch(chosen);
      } else {
        submitSearch();
      }
    } else if (e.key === "Escape") {
      setOpen(false);
    }
  };

  return (
    <div className="search-box-wrap">
      <div className="search-input-row">
        <svg className="search-icon" width="18" height="18" viewBox="0 0 24 24" fill="none">
          <circle cx="11" cy="11" r="7" stroke="var(--text-dim)" strokeWidth="2" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" stroke="var(--text-dim)" strokeWidth="2" strokeLinecap="round" />
        </svg>
        <input
          ref={inputRef}
          className="search-input"
          type="text"
          value={value}
          placeholder="Start typing… try “myspace” or “google”"
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={handleKeyDown}
          onFocus={() => suggestions.length > 0 && setOpen(true)}
          autoComplete="off"
          spellCheck="false"
          aria-autocomplete="list"
          aria-expanded={open}
          role="combobox"
        />
        {loading && <div className="search-spinner" aria-label="Loading" />}
        <button className="search-submit-btn" onClick={() => submitSearch()}>
          Search
        </button>
      </div>

      {error && <div className="search-error">{error}</div>}
      {submitMsg && <div className="search-toast mono">✓ {submitMsg}</div>}

      {open && suggestions.length > 0 && (
        <ul className="suggestion-list" role="listbox">
          {suggestions.map((s, i) => (
            <li
              key={s.query}
              role="option"
              aria-selected={i === highlightIndex}
              className={`suggestion-item ${i === highlightIndex ? "highlighted" : ""}`}
              onMouseEnter={() => setHighlightIndex(i)}
              onMouseDown={(e) => {
                e.preventDefault();
                setValue(s.query);
                submitSearch(s.query);
              }}
            >
              <span className="suggestion-query">{highlightMatch(s.query, value)}</span>
              <span className="suggestion-count mono">{formatCount(s.count)}</span>
            </li>
          ))}
        </ul>
      )}

      {open && suggestions.length === 0 && !loading && value.trim() && (
        <div className="suggestion-empty mono">no matches for “{value.trim()}”</div>
      )}
    </div>
  );
}

function highlightMatch(text, query) {
  const q = query.trim().toLowerCase();
  if (!q || !text.toLowerCase().startsWith(q)) return text;
  return (
    <>
      <span className="match-highlight">{text.slice(0, q.length)}</span>
      {text.slice(q.length)}
    </>
  );
}

function formatCount(n) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M";
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "K";
  return String(n);
}
