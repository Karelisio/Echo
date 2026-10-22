package com.karelisio.echo.echo

/** Phrase personnalisée envoyée avec chaque écho, affichée dans la notification de l'autre. */
object EchoMessage {
    /** Longueur maximale saisie dans l'appli. */
    const val MAX_LENGTH = 120

    /** Longueur maximale acceptée à la réception (celle du relais). */
    const val MAX_RECEIVED_LENGTH = 200

    /** Une seule ligne, sans caractères de contrôle ni espaces superflus ; `null` si rien ne reste. */
    fun clean(raw: String?, maxLength: Int = MAX_LENGTH): String? {
        val singleLine = buildString {
            var pendingSpace = false
            for (char in raw.orEmpty()) {
                if (char.isISOControl() || char.isWhitespace()) {
                    pendingSpace = isNotEmpty()
                } else {
                    if (pendingSpace) append(' ')
                    append(char)
                    pendingSpace = false
                }
            }
        }
        return truncate(singleLine, maxLength).trimEnd().ifEmpty { null }
    }

    /** Texte en cours de saisie : une seule ligne et pas plus de [MAX_LENGTH] caractères. */
    fun draft(raw: String): String = truncate(raw.replace('\n', ' '), MAX_LENGTH)

    /** Coupe à [max] caractères sans séparer les deux moitiés d'un emoji (paire de substitution). */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }
}
