package com.phonestream.app.update

import org.json.JSONObject

/** Where new versions are published. */
object Updates {
    const val REPO = "Jadiac5/PhoneToTv"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** The only place an update may be downloaded from. */
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
}

/** A published version that can be installed. [version] has no leading "v". */
data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    val size: Long,
    /** Lower-case hex SHA-256 of the APK as published by GitHub, if it said so. */
    val sha256: String?,
    val notes: String,
    val pageUrl: String,
)

/** "1.10.2" / "v1.10.2" / "1.10.2-beta1" -> [1, 10, 2]. Only the leading numeric, dot-separated part counts. */
object Version {
    fun parse(text: String): List<Int>? {
        var s = text.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val end = s.indexOfFirst { !(it.isDigit() || it == '.') }.let { if (it < 0) s.length else it }
        val parts = s.substring(0, end).split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() || it.length > 9 }) return null
        return parts.map { it.toInt() }
    }

    /** "v1.2.0" -> "1.2.0"; null if there is no version number in it. */
    fun normalize(text: String): String? = parse(text)?.joinToString(".")

    /** Is [candidate] a higher version than [current]? Anything unreadable counts as "not newer". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

object ReleaseParser {
    /**
     * Reads the answer of GitHub's "latest release" endpoint. Null if it is not a usable release
     * (no version in the tag, or no APK attached that is hosted by our own repository).
     */
    fun parse(json: String): ReleaseInfo? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = Version.normalize(o.optString("tag_name", "")) ?: return null
        val assets = o.optJSONArray("assets") ?: return null

        var preferred: JSONObject? = null
        var fallback: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (!a.optString("browser_download_url", "").startsWith(Updates.DOWNLOAD_PREFIX)) continue
            if (name.startsWith("PhoneStream", ignoreCase = true)) {
                if (preferred == null) preferred = a
            } else if (fallback == null) fallback = a
        }
        val asset = preferred ?: fallback ?: return null

        val digest = asset.optString("digest", "").lowercase()
        val sha = if (digest.startsWith("sha256:") && digest.length == 7 + 64 && digest.substring(7).all { it in '0'..'9' || it in 'a'..'f' }) {
            digest.substring(7)
        } else null

        return ReleaseInfo(
            version = version,
            apkUrl = asset.getString("browser_download_url"),
            size = asset.optLong("size", 0L),
            sha256 = sha,
            notes = o.optString("body", "").trim(),
            pageUrl = o.optString("html_url", ""),
        )
    }
}
