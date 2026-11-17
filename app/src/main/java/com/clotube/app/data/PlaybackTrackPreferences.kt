package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.Locale

/**
 * Dedicated DataStore for persistent audio and subtitle track preferences.
 */
val Context.trackPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "clotube_track_prefs")

/**
 * Immutable snapshot of active sticky audio and subtitle track preferences.
 */
@Keep
data class TrackPreferenceState(
    val subtitlesEnabled: Boolean = false,
    val preferredSubtitleLang: String = "",
    val preferredSubtitleLabel: String = "",
    val preferredSubtitleIndex: Int = -1,
    val subtitleScalePercent: Int = 100,
    val preferredAudioLang: String = "",
    val preferredAudioLabel: String = ""
) : Serializable

/**
 * Manages sticky track preferences matching Clotube Desktop behavior:
 * persists user audio/subtitle selections to disk and normalizes language codes
 * (ISO-639-1, ISO-639-2, or human-readable names) and track labels so they match across
 * different media files and episode transitions.
 */
object PlaybackTrackPreferences {

    private const val TAG = "ClotubeTracks"

    private val KEY_SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
    private val KEY_PREFERRED_SUB_LANG = stringPreferencesKey("preferred_subtitle_lang")
    private val KEY_PREFERRED_SUB_LABEL = stringPreferencesKey("preferred_subtitle_label")
    private val KEY_PREFERRED_SUB_INDEX = intPreferencesKey("preferred_subtitle_index")
    private val KEY_SUBTITLE_SCALE_PERCENT = intPreferencesKey("subtitle_scale_percent")
    private val KEY_PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_lang")
    private val KEY_PREFERRED_AUDIO_LABEL = stringPreferencesKey("preferred_audio_label")

    /**
     * Normalizes diverse language strings (e.g., "English [eng]", "en", "eng", "Japanese", "ja")
     * into canonical 3-letter ISO-639-2 tokens so cross-file matching is deterministic.
     */
    fun normalizeLanguageToken(rawTrackNameOrCode: String?): String {
        if (rawTrackNameOrCode.isNullOrBlank()) return ""

        val raw = rawTrackNameOrCode.trim().lowercase(Locale.ROOT)

        // 1. Check for embedded 3-letter codes inside brackets or parens (e.g. "[eng]", "(jpn)")
        val bracketMatch = Regex("""[\[\(]([a-z]{2,3})[\]\)]""").find(raw)
        if (bracketMatch != null) {
            val code = bracketMatch.groupValues[1]
            val mapped = mapIsoCode(code)
            if (mapped.isNotEmpty()) return mapped
        }

        // 2. Known language keywords
        return when {
            raw.contains("eng") || raw.contains("english") -> "eng"
            raw.contains("jpn") || raw.contains("japanese") || raw.contains("japan") -> "jpn"
            raw.contains("hin") || raw.contains("hindi") -> "hin"
            raw.contains("spa") || raw.contains("spanish") || raw.contains("español") -> "spa"
            raw.contains("fra") || raw.contains("fre") || raw.contains("french") || raw.contains("français") -> "fra"
            raw.contains("ger") || raw.contains("deu") || raw.contains("german") || raw.contains("deutsch") -> "ger"
            raw.contains("kor") || raw.contains("korean") -> "kor"
            raw.contains("chi") || raw.contains("zho") || raw.contains("chinese") -> "chi"
            raw.contains("ita") || raw.contains("italian") -> "ita"
            raw.contains("rus") || raw.contains("russian") -> "rus"
            raw.contains("por") || raw.contains("portuguese") -> "por"
            raw.contains("ara") || raw.contains("arabic") -> "ara"
            raw.contains("tur") || raw.contains("turkish") -> "tur"
            raw.contains("tam") || raw.contains("tamil") -> "tam"
            raw.contains("tel") || raw.contains("telugu") -> "tel"
            raw.contains("ben") || raw.contains("bengali") || raw.contains("bangla") -> "ben"
            else -> {
                // Extract standalone 2 or 3-letter alphabetic token
                val token = Regex("""\b[a-z]{2,3}\b""").find(raw)?.value ?: ""
                if (token.isNotEmpty()) mapIsoCode(token) else raw.take(3)
            }
        }
    }

    private fun mapIsoCode(code: String): String = when (code) {
        "en" -> "eng"
        "ja" -> "jpn"
        "hi" -> "hin"
        "es" -> "spa"
        "fr" -> "fra"
        "de" -> "ger"
        "ko" -> "kor"
        "zh" -> "chi"
        "it" -> "ita"
        "ru" -> "rus"
        "pt" -> "por"
        "ar" -> "ara"
        "tr" -> "tur"
        "ta" -> "tam"
        "te" -> "tel"
        "bn" -> "ben"
        else -> code
    }

    /**
     * Normalizes a human-readable track label by cleaning whitespace and casing,
     * e.g., "English [SDH]" -> "english [sdh]".
     */
    fun normalizeTrackLabel(rawTrackName: String?): String {
        if (rawTrackName.isNullOrBlank()) return ""
        return rawTrackName
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * Persists subtitle selection using NonCancellable + Dispatchers.IO so episode
     * transitions never interrupt write operations.
     */
    suspend fun saveSubtitleSelection(
        context: Context,
        trackId: Int,
        trackName: String?,
        activeTrackIndex: Int
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (trackId == -1 || trackName.isNullOrBlank() ||
            trackName.equals("Disable", ignoreCase = true) ||
            trackName.equals("Off", ignoreCase = true)
        ) {
            context.trackPrefsDataStore.edit { prefs ->
                prefs[KEY_SUBTITLES_ENABLED] = false
                prefs[KEY_PREFERRED_SUB_LANG] = ""
                prefs[KEY_PREFERRED_SUB_LABEL] = ""
                prefs[KEY_PREFERRED_SUB_INDEX] = -1
            }
            Log.i(TAG, "Saved sticky subtitle -> enabled=false, lang='', label='', idx=-1")
        } else {
            val normLang = normalizeLanguageToken(trackName)
            val normLabel = normalizeTrackLabel(trackName)
            context.trackPrefsDataStore.edit { prefs ->
                prefs[KEY_SUBTITLES_ENABLED] = true
                prefs[KEY_PREFERRED_SUB_LANG] = normLang
                prefs[KEY_PREFERRED_SUB_LABEL] = normLabel
                prefs[KEY_PREFERRED_SUB_INDEX] = activeTrackIndex
            }
            Log.i(TAG, "Saved sticky subtitle -> enabled=true, lang='$normLang', label='$normLabel', idx=$activeTrackIndex")
        }
    }

    /**
     * Persists audio selection using NonCancellable + Dispatchers.IO.
     */
    suspend fun saveAudioSelection(
        context: Context,
        trackId: Int,
        trackName: String?
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val normLang = normalizeLanguageToken(trackName)
        val normLabel = normalizeTrackLabel(trackName)
        context.trackPrefsDataStore.edit { prefs ->
            prefs[KEY_PREFERRED_AUDIO_LANG] = normLang
            prefs[KEY_PREFERRED_AUDIO_LABEL] = normLabel
        }
        Log.i(TAG, "Saved sticky audio -> lang='$normLang', label='$normLabel'")
    }

    /**
     * Backward-compatible delegation for subtitle persistence.
     */
    suspend fun saveSubtitlePreference(context: Context, trackNameOrCode: String?, enabled: Boolean) {
        if (enabled && !trackNameOrCode.isNullOrBlank()) {
            saveSubtitleSelection(context, 0, trackNameOrCode, 0)
        } else {
            saveSubtitleSelection(context, -1, "Off", -1)
        }
    }

    /**
     * Backward-compatible delegation for audio persistence.
     */
    suspend fun saveAudioPreference(context: Context, trackNameOrCode: String) {
        saveAudioSelection(context, 0, trackNameOrCode)
    }

    /**
     * Persists subtitle font scaling percentage (clamped between 60% and 200%).
     */
    suspend fun saveSubtitleScale(
        context: Context,
        scalePercent: Int
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val clamped = scalePercent.coerceIn(60, 200)
        context.trackPrefsDataStore.edit { prefs ->
            prefs[KEY_SUBTITLE_SCALE_PERCENT] = clamped
        }
        Log.i(TAG, "Saved sticky subtitle scale -> $clamped%")
    }

    /**
     * Reads current sticky track preferences snapshot.
     */
    suspend fun getTrackPreferences(context: Context): TrackPreferenceState = withContext(Dispatchers.IO) {
        context.trackPrefsDataStore.data.map { prefs ->
            TrackPreferenceState(
                subtitlesEnabled = prefs[KEY_SUBTITLES_ENABLED] ?: false,
                preferredSubtitleLang = prefs[KEY_PREFERRED_SUB_LANG] ?: "",
                preferredSubtitleLabel = prefs[KEY_PREFERRED_SUB_LABEL] ?: "",
                preferredSubtitleIndex = prefs[KEY_PREFERRED_SUB_INDEX] ?: -1,
                subtitleScalePercent = prefs[KEY_SUBTITLE_SCALE_PERCENT] ?: 100,
                preferredAudioLang = prefs[KEY_PREFERRED_AUDIO_LANG] ?: "",
                preferredAudioLabel = prefs[KEY_PREFERRED_AUDIO_LABEL] ?: ""
            )
        }.first()
    }

    /**
     * Reactive stream of track preferences.
     */
    fun getTrackPreferencesFlow(context: Context): Flow<TrackPreferenceState> {
        return context.trackPrefsDataStore.data.map { prefs ->
            TrackPreferenceState(
                subtitlesEnabled = prefs[KEY_SUBTITLES_ENABLED] ?: false,
                preferredSubtitleLang = prefs[KEY_PREFERRED_SUB_LANG] ?: "",
                preferredSubtitleLabel = prefs[KEY_PREFERRED_SUB_LABEL] ?: "",
                preferredSubtitleIndex = prefs[KEY_PREFERRED_SUB_INDEX] ?: -1,
                subtitleScalePercent = prefs[KEY_SUBTITLE_SCALE_PERCENT] ?: 100,
                preferredAudioLang = prefs[KEY_PREFERRED_AUDIO_LANG] ?: "",
                preferredAudioLabel = prefs[KEY_PREFERRED_AUDIO_LABEL] ?: ""
            )
        }
    }

    /**
     * Observable alias for track preferences.
     */
    fun observeTrackPreferences(context: Context): Flow<TrackPreferenceState> = getTrackPreferencesFlow(context)

    /**
     * Resets sticky track preferences to defaults.
     */
    suspend fun resetTrackPreferences(context: Context) = withContext(NonCancellable + Dispatchers.IO) {
        context.trackPrefsDataStore.edit { prefs ->
            prefs.remove(KEY_SUBTITLES_ENABLED)
            prefs.remove(KEY_PREFERRED_SUB_LANG)
            prefs.remove(KEY_PREFERRED_SUB_LABEL)
            prefs.remove(KEY_PREFERRED_SUB_INDEX)
            prefs.remove(KEY_SUBTITLE_SCALE_PERCENT)
            prefs.remove(KEY_PREFERRED_AUDIO_LANG)
            prefs.remove(KEY_PREFERRED_AUDIO_LABEL)
        }
    }

    /**
     * 4-Tier Deterministic Subtitle Track Matcher:
     * Evaluates available tracks against persisted user preferences.
     *
     * @param tracks List of (trackId, trackName) pairs loaded by LibVLC.
     * @param prefs Current persisted track preferences.
     * @return Track ID to apply, -1 for explicitly Off, or null if tracks are not loaded yet.
     */
    fun findMatchingSubtitleTrackId(
        tracks: List<Pair<Int, String>>,
        prefs: TrackPreferenceState
    ): Int? {
        if (!prefs.subtitlesEnabled) return -1
        if (tracks.isEmpty()) return null

        val candidates = tracks.filter { (id, name) ->
            id != -1 &&
            !name.equals("Disable", ignoreCase = true) &&
            !name.equals("Off", ignoreCase = true)
        }
        if (candidates.isEmpty()) return null

        // Tier 1 (Exact Normalized Label Match)
        if (prefs.preferredSubtitleLabel.isNotBlank()) {
            val tier1 = candidates.firstOrNull { (_, name) ->
                normalizeTrackLabel(name) == prefs.preferredSubtitleLabel
            }
            if (tier1 != null) {
                Log.d(TAG, "Tier 1 exact label match: '${tier1.second}' (id=${tier1.first})")
                return tier1.first
            }
        }

        // Tier 2 (Normalized Language Token Match)
        if (prefs.preferredSubtitleLang.isNotBlank() && prefs.preferredSubtitleLang != "und") {
            val tier2 = candidates.firstOrNull { (_, name) ->
                normalizeLanguageToken(name) == prefs.preferredSubtitleLang
            }
            if (tier2 != null) {
                Log.d(TAG, "Tier 2 language token match: '${tier2.second}' (id=${tier2.first})")
                return tier2.first
            }
        }

        // Tier 3 (Partial Label Keyword Overlap)
        if (prefs.preferredSubtitleLabel.isNotBlank()) {
            val significantWords = prefs.preferredSubtitleLabel
                .split(Regex("""[\s\-_\[\]\(\),.]+"""))
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.length >= 3 && it !in setOf("the", "and", "for", "with", "track", "sub", "subtitle") }

            if (significantWords.isNotEmpty()) {
                val tier3 = candidates.firstOrNull { (_, name) ->
                    val norm = normalizeTrackLabel(name)
                    significantWords.any { word -> norm.contains(word) }
                }
                if (tier3 != null) {
                    Log.d(TAG, "Tier 3 keyword overlap match: '${tier3.second}' (id=${tier3.first})")
                    return tier3.first
                }
            }
        }

        // Tier 4 (Track Index Fallback for generic unlabeled tracks e.g. "Track 1", "Track 2")
        val hasOnlyGenericNames = candidates.all { (_, name) ->
            val lower = name.trim().lowercase(Locale.ROOT)
            lower.matches(Regex("""^(track|subtitle|stream)?\s*\d+$""")) || lower.isBlank()
        }
        if (hasOnlyGenericNames && prefs.preferredSubtitleIndex in candidates.indices) {
            val tier4 = candidates[prefs.preferredSubtitleIndex]
            Log.d(TAG, "Tier 4 generic index match: idx=${prefs.preferredSubtitleIndex} -> '${tier4.second}' (id=${tier4.first})")
            return tier4.first
        }

        // Fallback: Preserve active captions rather than turning off
        val fallback = candidates.first()
        Log.d(TAG, "Fallback caption match: '${fallback.second}' (id=${fallback.first})")
        return fallback.first
    }

    /**
     * Multi-Tier Audio Track Matcher:
     * Evaluates available audio tracks against persisted user preferences.
     */
    fun findMatchingAudioTrackId(
        tracks: List<Pair<Int, String>>,
        prefs: TrackPreferenceState
    ): Int? {
        if (tracks.isEmpty()) return null
        val candidates = tracks.filter { it.first != -1 }
        if (candidates.isEmpty()) return null

        // Tier 1 (Exact Normalized Label Match)
        if (prefs.preferredAudioLabel.isNotBlank()) {
            val tier1 = candidates.firstOrNull { (_, name) ->
                normalizeTrackLabel(name) == prefs.preferredAudioLabel
            }
            if (tier1 != null) {
                Log.d(TAG, "Audio Tier 1 exact label match: '${tier1.second}' (id=${tier1.first})")
                return tier1.first
            }
        }

        // Tier 2 (Normalized Language Token Match)
        if (prefs.preferredAudioLang.isNotBlank() && prefs.preferredAudioLang != "und") {
            val tier2 = candidates.firstOrNull { (_, name) ->
                normalizeLanguageToken(name) == prefs.preferredAudioLang
            }
            if (tier2 != null) {
                Log.d(TAG, "Audio Tier 2 language token match: '${tier2.second}' (id=${tier2.first})")
                return tier2.first
            }
        }

        // Tier 3 (Partial Label Keyword Overlap)
        if (prefs.preferredAudioLabel.isNotBlank()) {
            val significantWords = prefs.preferredAudioLabel
                .split(Regex("""[\s\-_\[\]\(\),.]+"""))
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.length >= 3 && it !in setOf("the", "and", "for", "with", "track", "audio") }

            if (significantWords.isNotEmpty()) {
                val tier3 = candidates.firstOrNull { (_, name) ->
                    val norm = normalizeTrackLabel(name)
                    significantWords.any { word -> norm.contains(word) }
                }
                if (tier3 != null) {
                    Log.d(TAG, "Audio Tier 3 keyword overlap match: '${tier3.second}' (id=${tier3.first})")
                    return tier3.first
                }
            }
        }

        return null
    }
}
