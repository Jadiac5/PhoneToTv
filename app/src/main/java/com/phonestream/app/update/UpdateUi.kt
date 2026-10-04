package com.phonestream.app.update

enum class UpdateAction { CHECK, UPDATE, NONE }

/**
 * What the one update button says and does for a given [UpdateState]. Kept free of Android classes so
 * the Settings screen and the little pill on the start screen can't disagree, and so it can be unit-tested.
 */
object UpdateUi {
    fun action(s: UpdateState): UpdateAction = when (s.phase) {
        UpdatePhase.IDLE, UpdatePhase.UP_TO_DATE -> UpdateAction.CHECK
        UpdatePhase.AVAILABLE -> UpdateAction.UPDATE
        UpdatePhase.ERROR -> if (s.info != null) UpdateAction.UPDATE else UpdateAction.CHECK
        UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING -> UpdateAction.NONE
    }

    fun buttonLabel(s: UpdateState): String = when (s.phase) {
        UpdatePhase.IDLE -> "Check for updates"
        UpdatePhase.CHECKING -> "Checking…"
        UpdatePhase.UP_TO_DATE -> "Check again"
        UpdatePhase.AVAILABLE -> "Update to v${s.info?.version ?: "?"}"
        UpdatePhase.DOWNLOADING -> "Downloading ${s.progress}%"
        UpdatePhase.INSTALLING -> "Installing…"
        UpdatePhase.ERROR -> if (s.info != null) "Try again" else "Check again"
    }

    /** The line under the version in Settings; empty = nothing to say. */
    fun status(s: UpdateState): String = when (s.phase) {
        UpdatePhase.IDLE -> ""
        UpdatePhase.CHECKING -> "Looking for a new version…"
        UpdatePhase.UP_TO_DATE -> s.message
        UpdatePhase.AVAILABLE -> listOfNotNull(
            "Version ${s.info?.version ?: "?"} is available${sizeText(s.info)}",
            s.message.ifEmpty { null },
        ).joinToString("\n")
        UpdatePhase.DOWNLOADING -> "Downloading version ${s.info?.version ?: "?"}…"
        UpdatePhase.INSTALLING -> s.message.ifEmpty { "Installing…" }
        UpdatePhase.ERROR -> s.message
    }

    /** Text of the small button on the start screen; null = no update is known, so no button. */
    fun pill(s: UpdateState): String? = when (s.phase) {
        UpdatePhase.AVAILABLE -> "Update available · v${s.info?.version ?: "?"}"
        UpdatePhase.DOWNLOADING -> "Downloading update… ${s.progress}%"
        UpdatePhase.INSTALLING -> "Installing update…"
        UpdatePhase.ERROR -> if (s.info != null) "Update failed · tap to retry" else null
        else -> null
    }

    private fun sizeText(info: ReleaseInfo?): String =
        if (info != null && info.size > 0) " (${"%.1f".format(info.size / 1_048_576.0)} MB)" else ""

    /** Release notes as shown in Settings: trimmed, and never a wall of text. */
    fun shortNotes(notes: String, max: Int = 600): String {
        val t = notes.trim()
        return if (t.length <= max) t else t.substring(0, max).trimEnd() + "…"
    }
}
