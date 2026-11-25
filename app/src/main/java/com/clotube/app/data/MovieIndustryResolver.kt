package com.clotube.app.data

import com.clotube.app.model.Movie
import com.clotube.app.model.VideoItem
import java.util.Locale

data class ResolvedIndustryInfo(
    val language: String,
    val industry: String,
    val sectionLabel: String,
    val isExplicitCategoryFolder: Boolean = false
)

object MovieIndustryResolver {

    private val SEPARATOR_REGEX = Regex("[._\\-–—/\\\\()\\[\\]]+")
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val GENERIC_VIDEO_STEMS = setOf(
        "movie", "video", "main", "film", "feature", "play", "default", "stream",
        "cd1", "cd2", "part1", "part2", "1080p", "720p", "2160p", "4k", "bluray", "webrip", "webdl"
    )

    private data class IndustryRule(
        val keywords: Set<String>,
        val language: String,
        val industry: String,
        val sectionLabel: String
    )

    private val INDUSTRY_RULES = listOf(
        IndustryRule(
            keywords = setOf("hindi", "bollywood", "hin", "hi"),
            language = "Hindi",
            industry = "Bollywood",
            sectionLabel = "Bollywood (Hindi)"
        ),
        IndustryRule(
            keywords = setOf("bengali", "bangla", "kolkata", "bangladeshi", "ben", "bng", "bn"),
            language = "Bengali",
            industry = "Tollywood (Bengali)",
            sectionLabel = "Tollywood (Bengali)"
        ),
        IndustryRule(
            keywords = setOf("telugu", "tollywood", "hyderabad", "tel", "te"),
            language = "Telugu",
            industry = "Tollywood (Telugu)",
            sectionLabel = "Tollywood (Telugu)"
        ),
        IndustryRule(
            keywords = setOf("english", "hollywood", "american", "british", "foreign", "eng", "en"),
            language = "English",
            industry = "Hollywood",
            sectionLabel = "Hollywood (English)"
        ),
        IndustryRule(
            keywords = setOf("tamil", "kollywood", "tam", "ta"),
            language = "Tamil",
            industry = "Kollywood",
            sectionLabel = "Kollywood (Tamil)"
        ),
        IndustryRule(
            keywords = setOf("malayalam", "mollywood", "mal", "ml"),
            language = "Malayalam",
            industry = "Mollywood",
            sectionLabel = "Mollywood (Malayalam)"
        ),
        IndustryRule(
            keywords = setOf("kannada", "sandalwood", "kan", "kn"),
            language = "Kannada",
            industry = "Sandalwood",
            sectionLabel = "Sandalwood (Kannada)"
        ),
        IndustryRule(
            keywords = setOf("marathi", "mar", "mr"),
            language = "Marathi",
            industry = "Marathi Cinema",
            sectionLabel = "Marathi Cinema"
        ),
        IndustryRule(
            keywords = setOf("punjabi", "pollywood", "panjabi", "pun", "pa"),
            language = "Punjabi",
            industry = "Pollywood",
            sectionLabel = "Pollywood (Punjabi)"
        ),
        IndustryRule(
            keywords = setOf("gujarati", "dhollywood", "guj", "gu"),
            language = "Gujarati",
            industry = "Dhollywood",
            sectionLabel = "Dhollywood (Gujarati)"
        ),
        IndustryRule(
            keywords = setOf("bhojpuri", "bhojwood", "bho"),
            language = "Bhojpuri",
            industry = "Bhojwood",
            sectionLabel = "Bhojwood (Bhojpuri)"
        ),
        IndustryRule(
            keywords = setOf("odia", "oriya", "ollywood", "ori", "or"),
            language = "Odia",
            industry = "Ollywood",
            sectionLabel = "Ollywood (Odia)"
        ),
        IndustryRule(
            keywords = setOf("korean", "kmovie", "kdrama", "kor", "ko"),
            language = "Korean",
            industry = "Korean Cinema",
            sectionLabel = "Korean Cinema"
        ),
        IndustryRule(
            keywords = setOf("japanese", "anime", "jpn", "ja"),
            language = "Japanese",
            industry = "Japanese Cinema",
            sectionLabel = "Japanese Cinema"
        ),
        IndustryRule(
            keywords = setOf("spanish", "espanol", "spa", "es"),
            language = "Spanish",
            industry = "Spanish Cinema",
            sectionLabel = "Spanish Cinema"
        ),
        IndustryRule(
            keywords = setOf("french", "francais", "fra", "fre", "fr"),
            language = "French",
            industry = "French Cinema",
            sectionLabel = "French Cinema"
        ),
        IndustryRule(
            keywords = setOf("german", "deutsch", "ger", "deu", "de"),
            language = "German",
            industry = "German Cinema",
            sectionLabel = "German Cinema"
        ),
        IndustryRule(
            keywords = setOf("chinese", "mandarin", "cantonese", "chi", "zho", "zh"),
            language = "Chinese",
            industry = "Chinese Cinema",
            sectionLabel = "Chinese Cinema"
        )
    )

    private val CATEGORY_SUFFIX_REGEX = Regex(
        "(?i)^(.+?)\\s+(movies|movie|films|film|cinema|collection|industry)$"
    )

    /**
     * Classifies a Google Drive subfolder under `Movies/` (e.g., "Bengali Movies", "Bengali_Movies",
     * "Hindi Movies", "English Movies", "Bollywood", "Tollywood", "Hollywood", or any custom folder)
     * when it represents a category directory rather than a single movie title folder.
     */
    fun classifyDriveFolder(folderName: String, isDirectChildOfMoviesRoot: Boolean): ResolvedIndustryInfo? {
        val raw = folderName.trim()
        if (raw.isBlank()) return null

        val hasReleaseYear = YEAR_REGEX.containsMatchIn(raw)
        val normalizedTokens = raw.lowercase()
            .replace(SEPARATOR_REGEX, " ")
            .split(" ")
            .filter { it.isNotBlank() }

        if (normalizedTokens.isEmpty()) return null

        // Avoid misclassifying movie folders like "English Vinglish (2012)" or "The English Patient"
        val noiseWords = setOf(
            "movies", "movie", "films", "film", "cinema", "collection", "industry",
            "hd", "4k", "1080p", "720p", "all"
        )
        val meaningfulTokens = normalizedTokens.filterNot { it in noiseWords }

        if (!hasReleaseYear && meaningfulTokens.size <= 2) {
            for (rule in INDUSTRY_RULES) {
                if (meaningfulTokens.any { it in rule.keywords }) {
                    return ResolvedIndustryInfo(
                        language = rule.language,
                        industry = rule.industry,
                        sectionLabel = rule.sectionLabel,
                        isExplicitCategoryFolder = true
                    )
                }
            }
        }

        // Support any custom category folder directly under `Movies/` (e.g., "Assamese Movies", "South Indian Movies")
        if (isDirectChildOfMoviesRoot && !hasReleaseYear) {
            val cleanedName = raw.replace(SEPARATOR_REGEX, " ").replace(Regex("\\s+"), " ").trim()
            val suffixMatch = CATEGORY_SUFFIX_REGEX.find(cleanedName)
            if (suffixMatch != null) {
                val categoryStem = suffixMatch.groupValues[1].trim()
                if (categoryStem.length in 3..28) {
                    val formattedLabel = categoryStem.split(" ").joinToString(" ") { word ->
                        word.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    return ResolvedIndustryInfo(
                        language = formattedLabel,
                        industry = "$formattedLabel Cinema",
                        sectionLabel = "$formattedLabel Cinema",
                        isExplicitCategoryFolder = true
                    )
                }
            }
        }

        return null
    }

    /**
     * Extracts an explicit language/industry tag from a video filename (skipping the first token
     * so titles like "English Vinglish" are not misclassified).
     */
    fun extractLanguageFromFileName(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        val tokens = stem.lowercase()
            .replace(SEPARATOR_REGEX, " ")
            .split(" ")
            .filter { it.isNotBlank() }

        val tagTokens = if (tokens.size > 1) tokens.drop(1) else emptyList()
        // Avoid matching short 2-letter ISO codes inside arbitrary filename tokens; check 3+ char keywords first
        for (rule in INDUSTRY_RULES) {
            if (tagTokens.any { tok -> tok.length >= 3 && tok in rule.keywords }) {
                return rule.language
            }
        }
        return null
    }

    /**
     * Deduces language from Unicode script blocks when non-Latin scripts appear in titles.
     */
    fun deduceLanguageFromScript(title: String): String {
        for (ch in title) {
            val code = ch.code
            when {
                code in 0x0980..0x09FF -> return "Bengali"
                code in 0x0900..0x097F -> return "Hindi"
                code in 0x0C00..0x0C7F -> return "Telugu"
                code in 0x0B80..0x0BFF -> return "Tamil"
                code in 0x0D00..0x0D7F -> return "Malayalam"
                code in 0x0C80..0x0CFF -> return "Kannada"
                code in 0x0A00..0x0A7F -> return "Punjabi"
                code in 0x0A80..0x0AFF -> return "Gujarati"
                code in 0x0B00..0x0B7F -> return "Odia"
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> return "Korean"
                code in 0x3040..0x30FF -> return "Japanese"
                code in 0x4E00..0x9FFF -> return "Chinese"
            }
        }
        return "English"
    }

    /**
     * Maps any language, ISO code, or industry label to its canonical [ResolvedIndustryInfo].
     */
    fun toIndustryInfo(languageOrIndustry: String?): ResolvedIndustryInfo {
        val raw = languageOrIndustry?.trim().orEmpty()
        if (raw.isBlank() || raw.equals("Unknown", ignoreCase = true) || raw.equals("und", ignoreCase = true)) {
            return ResolvedIndustryInfo(
                language = "English",
                industry = "Hollywood",
                sectionLabel = "Hollywood (English)"
            )
        }

        val tokens = raw.lowercase()
            .replace(SEPARATOR_REGEX, " ")
            .split(" ")
            .filter { it.isNotBlank() }

        for (rule in INDUSTRY_RULES) {
            if (tokens.any { it in rule.keywords }) {
                return ResolvedIndustryInfo(
                    language = rule.language,
                    industry = rule.industry,
                    sectionLabel = rule.sectionLabel
                )
            }
        }

        val formatted = raw.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString()
        }
        val industryName = if (
            formatted.endsWith("Cinema", ignoreCase = true) ||
            formatted.endsWith("wood", ignoreCase = true)
        ) {
            formatted
        } else {
            "$formatted Cinema"
        }
        return ResolvedIndustryInfo(
            language = formatted,
            industry = industryName,
            sectionLabel = industryName
        )
    }

    /**
     * Resolves the canonical section label (e.g., "Bollywood (Hindi)", "Tollywood (Bengali)",
     * "Hollywood (English)") for any [VideoItem].
     */
    fun resolveVideoSection(item: VideoItem): String {
        if (item.industry.isNotBlank() && !item.industry.equals("Unknown", ignoreCase = true)) {
            return toIndustryInfo(item.industry).sectionLabel
        }
        if (item.language.isNotBlank() && !item.language.equals("Unknown", ignoreCase = true)) {
            return toIndustryInfo(item.language).sectionLabel
        }
        if (item.sourceFolder.isNotBlank()) {
            classifyDriveFolder(item.sourceFolder, isDirectChildOfMoviesRoot = true)?.let {
                return it.sectionLabel
            }
        }
        return toIndustryInfo(deduceLanguageFromScript(item.displayTitle.ifBlank { item.title })).sectionLabel
    }

    /**
     * Resolves the canonical section label for any [Movie].
     */
    fun resolveMovieSection(movie: Movie): String {
        if (movie.industry.isNotBlank() && !movie.industry.equals("Unknown", ignoreCase = true)) {
            return toIndustryInfo(movie.industry).sectionLabel
        }
        if (movie.language.isNotBlank() && !movie.language.equals("Unknown", ignoreCase = true)) {
            return toIndustryInfo(movie.language).sectionLabel
        }
        val folderCandidate = movie.sourceFolder.ifBlank { movie.parentFolder }
        if (folderCandidate.isNotBlank()) {
            classifyDriveFolder(folderCandidate, isDirectChildOfMoviesRoot = true)?.let {
                return it.sectionLabel
            }
        }
        return toIndustryInfo(deduceLanguageFromScript(movie.title)).sectionLabel
    }

    fun isGenericVideoFileName(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.')
            .lowercase()
            .replace(SEPARATOR_REGEX, "")
            .trim()
        return stem.isBlank() || stem in GENERIC_VIDEO_STEMS
    }

    /**
     * Orders major lingual industries first, followed alphabetically by any additional
     * industries discovered in the user's Google Drive.
     */
    fun sortIndustrySections(sections: Collection<String>): List<String> {
        val priorityOrder = listOf(
            "Bollywood (Hindi)",
            "Tollywood (Bengali)",
            "Tollywood (Telugu)",
            "Hollywood (English)",
            "Kollywood (Tamil)",
            "Mollywood (Malayalam)",
            "Sandalwood (Kannada)",
            "Marathi Cinema",
            "Pollywood (Punjabi)",
            "Dhollywood (Gujarati)",
            "Bhojwood (Bhojpuri)",
            "Ollywood (Odia)",
            "Korean Cinema",
            "Japanese Cinema"
        )
        return sections.distinct().sortedWith(
            compareBy<String> { section ->
                val idx = priorityOrder.indexOf(section)
                if (idx >= 0) idx else priorityOrder.size
            }.thenBy { it.lowercase() }
        )
    }
}
