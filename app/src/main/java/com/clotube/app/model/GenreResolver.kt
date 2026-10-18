package com.clotube.app.model

/**
 * Genre resolution engine extracting genre metadata from:
 * 1. Explicit bracket or parentheses tags in filenames/folder names (e.g. "[Action]", "(Sci-Fi)").
 * 2. Keyword matching on known titles (e.g. "Mentalist" -> "Crime • Mystery • Drama").
 * 3. Contextual defaults ("Movie • Cinema" for films, "TV Series • Drama" for series).
 */
object GenreResolver {

    private val BRACKET_TAG_REGEX = Regex("""[\[\(]([A-Za-z0-9\s\-/&]+)[\]\)]""")

    private val KNOWN_GENRES = listOf(
        "action", "adventure", "animation", "anime", "biography", "comedy",
        "crime", "documentary", "drama", "family", "fantasy", "history",
        "horror", "music", "musical", "mystery", "romance", "sci-fi",
        "science fiction", "sport", "superhero", "thriller", "war", "western"
    )

    private val TITLE_KEYWORD_RULES = listOf(
        Regex("(?i)mentalist") to "Crime • Mystery • Drama",
        Regex("(?i)(jack\\s*reacher|reacher)") to "Action • Thriller",
        Regex("(?i)interstellar") to "Sci-Fi • Adventure • Drama",
        Regex("(?i)oppenheimer") to "Biography • Drama • History",
        Regex("(?i)breaking\\s*bad") to "Crime • Drama • Thriller",
        Regex("(?i)better\\s*call\\s*saul") to "Crime • Drama",
        Regex("(?i)stranger\\s*things") to "Sci-Fi • Horror • Drama",
        Regex("(?i)game\\s*of\\s*thrones") to "Fantasy • Action • Drama",
        Regex("(?i)house\\s*of\\s*the\\s*dragon") to "Fantasy • Action • Drama",
        Regex("(?i)(dark\\s*knight|batman)") to "Action • Crime • Drama",
        Regex("(?i)spider[-\\s]?man") to "Action • Adventure • Sci-Fi",
        Regex("(?i)avengers") to "Action • Sci-Fi • Adventure",
        Regex("(?i)matrix") to "Sci-Fi • Action",
        Regex("(?i)gladiator") to "Action • Drama • Adventure",
        Regex("(?i)inception") to "Sci-Fi • Action • Thriller",
        Regex("(?i)avatar") to "Sci-Fi • Action • Adventure",
        Regex("(?i)dune") to "Sci-Fi • Adventure • Drama",
        Regex("(?i)godfather") to "Crime • Drama",
        Regex("(?i)pulp\\s*fiction") to "Crime • Drama",
        Regex("(?i)fight\\s*club") to "Drama • Thriller",
        Regex("(?i)forrest\\s*gump") to "Drama • Romance",
        Regex("(?i)sherlock") to "Crime • Mystery • Drama",
        Regex("(?i)true\\s*detective") to "Crime • Drama • Mystery",
        Regex("(?i)fargo") to "Crime • Drama • Thriller",
        Regex("(?i)succession") to "Drama",
        Regex("(?i)the\\s*boys") to "Action • Comedy • Sci-Fi",
        Regex("(?i)severance") to "Sci-Fi • Mystery • Thriller",
        Regex("(?i)peaky\\s*blinders") to "Crime • Drama",
        Regex("(?i)vikings") to "Action • Adventure • Drama",
        Regex("(?i)chernobyl") to "Drama • History • Thriller",
        Regex("(?i)narcos") to "Biography • Crime • Drama",
        Regex("(?i)ted\\s*lasso") to "Comedy • Drama • Sport",
        Regex("(?i)mandalorian") to "Action • Adventure • Sci-Fi",
        Regex("(?i)witcher") to "Action • Adventure • Fantasy",
        Regex("(?i)westworld") to "Drama • Mystery • Sci-Fi",
        Regex("(?i)black\\s*mirror") to "Drama • Mystery • Sci-Fi"
    )

    fun resolveGenre(name: String, isSeries: Boolean): String {
        // 1. Bracket or parentheses tags (e.g. [Action], (Sci-Fi), [Crime / Drama])
        val matches = BRACKET_TAG_REGEX.findAll(name)
        for (match in matches) {
            val tagText = match.groupValues[1].trim()
            val lower = tagText.lowercase()
            if (KNOWN_GENRES.any { lower.contains(it) }) {
                return formatTagText(tagText)
            }
        }

        // 2. Keyword rules based on title
        for ((regex, genre) in TITLE_KEYWORD_RULES) {
            if (regex.containsMatchIn(name)) {
                return genre
            }
        }

        // 3. Fallback to contextual defaults
        return if (isSeries) "TV Series • Drama" else "Movie • Cinema"
    }

    private fun formatTagText(tag: String): String {
        val delimiters = charArrayOf('/', '-', '&', ',', '|')
        val tokens = tag.split(*delimiters).map { it.trim() }
        val matchedTokens = tokens.filter { token ->
            val lower = token.lowercase()
            KNOWN_GENRES.any { lower.contains(it) }
        }
        return if (matchedTokens.isNotEmpty()) {
            matchedTokens.joinToString(" • ") { it.replaceFirstChar { c -> c.uppercase() } }
        } else {
            tag.trim().replaceFirstChar { it.uppercase() }
        }
    }
}
