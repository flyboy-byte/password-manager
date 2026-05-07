package com.example.passwordmanager.service

import android.util.Log
import com.example.passwordmanager.data.PasswordEntry
import java.util.Locale

object SmartMatcher {

    private const val TAG = "SmartMatcher"

    private val NOISE_SEGMENTS = setOf(
        "com", "org", "net", "io", "app", "dev",
        "android", "google", "apps", "mobile",
        "www", "m", "login", "signin", "auth"
    )

    data class MatchResult(
        val entry: PasswordEntry,
        val score: Int,
        val reason: String,
    )

    fun isMatch(vaultTitle: String, webDomain: String?, appPackage: String): Boolean {
        return scoreMatch(vaultTitle, webDomain, appPackage) > 0
    }

    fun rankMatches(entries: List<PasswordEntry>, webDomain: String?, appPackage: String): List<MatchResult> {
        val results = entries.map { entry ->
            val score = scoreMatch(entry.title, webDomain, appPackage)
            val reason = explainMatch(entry.title, webDomain, appPackage)
            MatchResult(entry = entry, score = score, reason = reason)
        }.filter { it.score > 0 }
            .sortedByDescending { it.score }

        Log.d(TAG, "rankMatches: domain=$webDomain pkg=$appPackage total=${entries.size} matched=${results.size}")

        // Extra diagnostics: log first N rejections with reason so we know why nothing matches.
        entries.take(10).forEach { entry ->
            val score = scoreMatch(entry.title, webDomain, appPackage)
            if (score <= 0) {
                Log.d(TAG, "reject: title=${entry.title} score=$score reason=${explainMatch(entry.title, webDomain, appPackage)}")
            }
        }

        results.take(10).forEachIndexed { index, r ->
            Log.d(TAG, "rank[$index]: title=${r.entry.title} score=${r.score} reason=${r.reason}")
        }

        return results
    }

    private fun scoreMatch(vaultTitle: String, webDomain: String?, appPackage: String): Int {
        val title = normalize(vaultTitle)
        if (title.isBlank()) return 0

        val domain = normalizeDomain(webDomain)
        val pkg = normalize(appPackage)

        val titleRoot = rootLike(title)
        val domainRoot = rootLike(domain)
        val pkgRoot = rootLike(pkg)

        // Strong exact/root matching first
        // Domain is for web credentials; package is for native app credentials.
        if (domain.isNotBlank() && title == domain) return 100
        if (domainRoot.isNotBlank() && titleRoot == domainRoot) return 95

        // If webDomain is missing (native app), match by appPackage without requiring a domain.
        if (webDomain.isNullOrBlank()) {
            if (title == pkg) return 90
            if (pkgRoot.isNotBlank() && titleRoot == pkgRoot) return 85
            // allow segment overlap by package name too
        } else {
            if (title == pkg) return 75
            if (pkgRoot.isNotBlank() && titleRoot == pkgRoot) return 70
        }

        // Segment overlap scoring
        val titleSeg = usefulSegments(title)
        val domainSeg = usefulSegments(domain)
        val pkgSeg = usefulSegments(pkg)

        val overlapDomain = titleSeg.intersect(domainSeg).size
        val overlapPkg = titleSeg.intersect(pkgSeg).size

        var score = 0
        score += overlapDomain * 30
        score += overlapPkg * 20

        // contains fallbacks (kept lower confidence)
        if (domain.isNotBlank() && (domain.contains(title) || title.contains(domain))) score += 20
        if (pkg.contains(title) || title.contains(pkg)) score += 15

        // extra small bump if root appears in domain/package
        if (titleRoot.isNotBlank() && domain.contains(titleRoot)) score += 20
        if (titleRoot.isNotBlank() && pkg.contains(titleRoot)) score += 10

        return score.coerceAtMost(100)
    }

    private fun explainMatch(vaultTitle: String, webDomain: String?, appPackage: String): String {
        val title = normalize(vaultTitle)
        val domain = normalizeDomain(webDomain)
        val pkg = normalize(appPackage)

        val titleRoot = rootLike(title)
        val domainRoot = rootLike(domain)
        val pkgRoot = rootLike(pkg)

        return when {
            domain.isNotBlank() && title == domain -> "exact-domain"
            domainRoot.isNotBlank() && titleRoot == domainRoot -> "root-domain"
            title == pkg -> "exact-package"
            pkgRoot.isNotBlank() && titleRoot == pkgRoot -> "root-package"
            usefulSegments(title).intersect(usefulSegments(domain)).isNotEmpty() -> "segment-domain"
            usefulSegments(title).intersect(usefulSegments(pkg)).isNotEmpty() -> "segment-package"
            else -> "contains-fallback"
        }
    }

    private fun normalizeDomain(raw: String?): String {
        val d = normalize(raw ?: "")
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return d
    }

    private fun rootLike(value: String): String {
        if (value.isBlank()) return ""

        val v = normalizeDomain(value)
        val dotParts = v.split('.')
            .filter { it.isNotBlank() && it !in NOISE_SEGMENTS }

        if (dotParts.isNotEmpty()) return dotParts.first()

        val pkgParts = v.split('_', '-', '.')
            .filter { it.isNotBlank() && it !in NOISE_SEGMENTS }

        return pkgParts.firstOrNull().orEmpty()
    }

    private fun usefulSegments(value: String): Set<String> {
        if (value.isBlank()) return emptySet()

        return value
            .split('.', '-', '_', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it !in NOISE_SEGMENTS }
            .filter { it.length >= 3 }
            .toSet()
    }

    private fun normalize(raw: String): String = raw.lowercase(Locale.ROOT).trim()
}
