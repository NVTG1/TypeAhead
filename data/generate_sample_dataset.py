#!/usr/bin/env python3
"""
generate_sample_dataset.py

Generates a synthetic dataset of 100,000+ (query, count) rows that mimics
realistic search-typeahead traffic: common prefixes (iphone, java, python,
etc.) with many related queries and a Zipf-like count distribution (a
handful of head queries with huge counts, a long tail with small counts).

This exists so the project can be run and demoed *today* without waiting
on the full AOL log file. It is a stand-in - swap it for
data/queries.csv produced by aggregate_aol_log.py against the real AOL
log whenever that's ready; the loader (DataLoader.java) doesn't care
which one produced the CSV, the format is identical.
"""
import csv
import random

random.seed(42)

# Seed terms across a few topical clusters, each with realistic
# prefix-overlapping variations - this is what makes typeahead demos
# look convincing (typing "iph" should show several real results).
CLUSTERS = {
    "iphone": [
        "iphone", "iphone 15", "iphone 15 pro", "iphone 15 pro max", "iphone case",
        "iphone charger", "iphone 14", "iphone 13", "iphone se", "iphone screen repair",
        "iphone deals", "iphone refurbished", "iphone vs android", "iphone wallpaper",
        "iphone update", "iphone backup", "iphone trade in", "iphone unlocked",
    ],
    "java": [
        "java", "java tutorial", "java download", "java spring boot", "java vs python",
        "java interview questions", "java 17 features", "java string methods",
        "java collections", "java streams", "java exception handling", "java jdk",
        "javascript", "javascript tutorial", "javascript array methods",
    ],
    "python": [
        "python", "python tutorial", "python download", "python list comprehension",
        "python pandas", "python for beginners", "python vs java", "python flask",
        "python django", "python interview questions", "python dictionary",
        "python data types", "python virtual environment",
    ],
    "samsung": [
        "samsung", "samsung galaxy s24", "samsung tv", "samsung phone", "samsung galaxy watch",
        "samsung fridge", "samsung washing machine", "samsung earbuds", "samsung monitor",
        "samsung galaxy buds", "samsung tab",
    ],
    "weather": [
        "weather", "weather today", "weather tomorrow", "weather forecast", "weather radar",
        "weather this weekend", "weather near me", "weather bengaluru", "weather mumbai",
        "weather delhi", "weather alerts",
    ],
    "recipe": [
        "recipe", "recipe for chicken curry", "recipe for pasta", "recipe for pancakes",
        "recipe for biryani", "recipe for dosa", "recipe easy dinner", "recipe vegetarian",
        "recipe for cake", "recipe instant pot",
    ],
    "movie": [
        "movie", "movie tickets", "movie times near me", "movie reviews", "movies 2026",
        "movie streaming", "movie download", "movie trailer", "movie box office",
    ],
    "news": [
        "news", "news today", "news india", "news world", "news technology",
        "news cricket score", "news stock market", "news headlines",
    ],
    "amazon": [
        "amazon", "amazon prime", "amazon login", "amazon order tracking", "amazon returns",
        "amazon customer service", "amazon fresh", "amazon prime video",
    ],
    "spring boot": [
        "spring boot", "spring boot tutorial", "spring boot rest api",
        "spring boot vs django", "spring boot security", "spring boot jpa",
        "spring boot microservices",
    ],
}

OTHER_PREFIXES = [
    "best", "top", "how to", "what is", "why does", "where is", "when is",
    "cheap", "free", "near me", "online", "review", "vs", "guide", "tips",
]
TOPICS = [
    "laptop", "headphones", "running shoes", "credit card", "insurance",
    "flight booking", "hotel", "car rental", "gym membership", "yoga mat",
    "coffee maker", "air purifier", "electric car", "solar panel", "vpn",
    "resume template", "interview tips", "stock market", "cryptocurrency",
    "machine learning", "data science", "artificial intelligence", "docker",
    "kubernetes", "react js", "node js", "sql tutorial", "system design",
    "consistent hashing", "load balancer", "redis cache", "postgres tutorial",
]


def zipf_count(rank: int, max_count: int = 120000) -> int:
    """Roughly Zipf-distributed: rank 1 gets max_count, decays as ~1/rank."""
    base = max_count / (rank ** 0.85)
    noise = random.uniform(0.8, 1.2)
    return max(1, int(base * noise))


def main():
    rows = []
    rank = 1

    # Cluster queries get higher, more structured counts (these are the
    # "head" of the distribution - the convincing typeahead demo data).
    for cluster, queries in CLUSTERS.items():
        for q in queries:
            rows.append((q, zipf_count(rank, max_count=150000)))
            rank += 1

    # Generate the long tail: combinations of prefix + topic, giving us
    # well over 100,000 distinct queries with realistically small counts.
    for prefix in OTHER_PREFIXES:
        for topic in TOPICS:
            q = f"{prefix} {topic}"
            rows.append((q, zipf_count(rank, max_count=150000)))
            rank += 1

    # Pad out to comfortably exceed the 100k minimum with randomized
    # long-tail single/double/triple word queries built from a vocabulary.
    # Use a numeric suffix to guarantee uniqueness in O(1) instead of
    # relying on random collisions to eventually miss (which can stall
    # once the combination space starts running dry).
    vocab = TOPICS + [w for qs in CLUSTERS.values() for q in qs for w in q.split()]
    vocab = sorted(set(vocab))
    target_total = 105_000
    seen = {q for q, _ in rows}

    templates = ["{a} {b}", "{a} {b} online", "{a} for {b}", "{b} {a}", "{a} {b} 2026", "{a} {b} guide"]

    counter = 0
    max_iterations = target_total * 50  # hard safety bound, just in case
    while len(rows) < target_total and counter < max_iterations:
        a = vocab[counter % len(vocab)]
        b = vocab[(counter // len(vocab)) % len(vocab)]
        template = templates[counter % len(templates)]
        if a == b:
            counter += 1
            continue
        q = template.format(a=a, b=b)
        counter += 1
        if q in seen:
            continue
        seen.add(q)
        rows.append((q, zipf_count(rank, max_count=150000)))
        rank += 1

    # Guaranteed-unique fallback: if the vocab/template combination space
    # was exhausted before reaching target_total, top up with numbered
    # synthetic queries so the dataset always meets the 100k minimum.
    fallback_n = 0
    while len(rows) < target_total:
        q = f"search term {fallback_n}"
        if q not in seen:
            seen.add(q)
            rows.append((q, zipf_count(rank, max_count=150000)))
            rank += 1
        fallback_n += 1

    random.shuffle(rows)  # don't ship the file pre-sorted by rank

    with open("queries.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["query", "count"])
        writer.writerows(rows)

    print(f"Wrote {len(rows)} rows to queries.csv")


if __name__ == "__main__":
    main()
