import sys
import os

# ════════════════════════════════════════════════════════════════════
# Search fix: match the PROVIDER name as well as the TMDB name.
#
# Cause: TMDB enrichment replaces item.name with the localized TMDB title,
# and search only matched item.name. So a movie the user had opened before
# (e.g. "Linterna Verde (2011)" -> "Green Lantern") stopped matching the
# provider name — results depended on click history.
#
# Fix: add originalName to MediaItem, set it from the provider at parse
# time, preserve it through every enrichment copy(), and have search match
# name OR originalName. `name` itself is untouched, so all TMDB logic
# (title resolution, localized display, metadata cache, similar-movie
# matching) is unaffected. Old caches without the field fall back to name.
# ════════════════════════════════════════════════════════════════════

model_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "model", "Models.kt")
client_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "network", "XtreamClient.kt")
vm_path = os.path.join("app", "src", "main", "java", "com", "qtone", "app", "MainViewModel.kt")

for p in [model_path, client_path, vm_path]:
    if not os.path.exists(p):
        print("ERROR: Could not find " + p)
        sys.exit(1)

# ── 1. Models.kt: add the field ──
with open(model_path, "r", encoding="utf-8") as f:
    m = f.read()
print("=== Models.kt ===")
old = '''    val id: String,
    val name: String,
    val streamType: String,'''
new = '''    val id: String,
    val name: String,
    // The provider's original title, kept even after TMDB enrichment
    // replaces `name` with the localized title. Search matches both so
    // results don't depend on which items the user has opened before.
    // Null on items from old caches (Gson default); search falls back to name.
    val originalName: String? = null,
    val streamType: String,'''
if "val originalName: String? = null" in m:
    print("SKIP: originalName already on MediaItem")
elif old not in m:
    print("ERROR: could not find MediaItem id/name/streamType fields")
    sys.exit(1)
else:
    m = m.replace(old, new, 1)
    with open(model_path, "w", encoding="utf-8") as f:
        f.write(m)
    print("OK: originalName added to MediaItem")

# ── 2. XtreamClient.kt: set it at parse time (movies + series) ──
with open(client_path, "r", encoding="utf-8") as f:
    c = f.read()
print("")
print("=== XtreamClient.kt ===")
n = 0
old_movie = '''                name = o.str("name") ?: "Movie",'''
new_movie = '''                name = o.str("name") ?: "Movie",
                originalName = o.str("name") ?: "Movie",'''
if new_movie in c:
    print("SKIP: movie originalName already set")
elif old_movie in c:
    c = c.replace(old_movie, new_movie, 1); n += 1
    print("OK: movie originalName set at parse")
else:
    print("WARN: movie name line not found")

old_series = '''                name = o.str("name") ?: "Series",'''
new_series = '''                name = o.str("name") ?: "Series",
                originalName = o.str("name") ?: "Series",'''
if new_series in c:
    print("SKIP: series originalName already set")
elif old_series in c:
    c = c.replace(old_series, new_series, 1); n += 1
    print("OK: series originalName set at parse")
else:
    print("WARN: series name line not found")

if n:
    with open(client_path, "w", encoding="utf-8") as f:
        f.write(c)

# ── 3. MainViewModel.kt: preserve through enrichment copies + search ──
with open(vm_path, "r", encoding="utf-8") as f:
    v = f.read()
print("")
print("=== MainViewModel.kt ===")

# Movie enrichment copy (appears in loadCache AND the refresh path — patch both)
old_mc = '''                movie.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: movie.name,
                    poster = movie.poster,'''
new_mc = '''                movie.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: movie.name,
                    // Keep the provider title so search still matches it after
                    // the display name switches to the TMDB localized title.
                    originalName = movie.originalName ?: movie.name,
                    poster = movie.poster,'''
cnt = v.count(old_mc)
if "originalName = movie.originalName ?: movie.name" in v:
    print("SKIP: movie copies already preserve originalName")
elif cnt == 0:
    print("ERROR: movie enrichment copy block not found")
    sys.exit(1)
else:
    v = v.replace(old_mc, new_mc)
    print("OK: movie enrichment copies preserve originalName (" + str(cnt) + " site(s))")

# Series enrichment copy (also appears twice)
old_sc = '''                item.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: item.name,
                    poster = item.poster,'''
new_sc = '''                item.copy(
                    name = cached.name.takeIf { it.isNotBlank() } ?: item.name,
                    // Keep the provider title so search still matches it after
                    // the display name switches to the TMDB localized title.
                    originalName = item.originalName ?: item.name,
                    poster = item.poster,'''
cnt = v.count(old_sc)
if "originalName = item.originalName ?: item.name" in v:
    print("SKIP: series copies already preserve originalName")
elif cnt == 0:
    print("ERROR: series enrichment copy block not found")
    sys.exit(1)
else:
    v = v.replace(old_sc, new_sc)
    print("OK: series enrichment copies preserve originalName (" + str(cnt) + " site(s))")

# Search: movies
old_ms = '''            _state.value.movies.filter { it.name.contains(q, ignoreCase = true) }.take(200)'''
new_ms = '''            _state.value.movies.filter {
                it.name.contains(q, ignoreCase = true) ||
                    (it.originalName?.contains(q, ignoreCase = true) == true)
            }.take(200)'''
if new_ms in v:
    print("SKIP: movie search already matches originalName")
elif old_ms not in v:
    print("ERROR: movie search filter not found")
    sys.exit(1)
else:
    v = v.replace(old_ms, new_ms, 1)
    print("OK: movie search matches name OR originalName")

# Search: series
old_ss = '''            _state.value.series.filter { it.name.contains(q, ignoreCase = true) }.take(200)'''
new_ss = '''            _state.value.series.filter {
                it.name.contains(q, ignoreCase = true) ||
                    (it.originalName?.contains(q, ignoreCase = true) == true)
            }.take(200)'''
if new_ss in v:
    print("SKIP: series search already matches originalName")
elif old_ss not in v:
    print("ERROR: series search filter not found")
    sys.exit(1)
else:
    v = v.replace(old_ss, new_ss, 1)
    print("OK: series search matches name OR originalName")

with open(vm_path, "w", encoding="utf-8") as f:
    f.write(v)

print("")
print("=" * 60)
print("Search now matches provider name AND TMDB name.")
print("`name` and all TMDB logic are unchanged.")
print("")
print("Test: search 'Linterna' -> all three 2011 entries;")
print("      search 'lantern'  -> all three as well.")
print("=" * 60)
