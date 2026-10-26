package dev.sam3vision.core.text

/**
 * Les modèles vision renvoient souvent du Markdown (`**gras**`, listes, liens) alors
 * que l'app affiche du texte brut dans des bulles. On nettoie sans toucher au fond.
 */
fun stripMarkdown(text: String): String {
    var out = text.trim()
    out = out.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    out = out.replace(Regex("""__(.+?)__"""), "$1")
    out = out.replace(Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)"""), "$1")
    out = out.replace(Regex("""`{1,3}([^`]*)`{1,3}"""), "$1")
    out = out.replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
    out = out.replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "• ")
    out = out.replace(Regex("""\[(https?://[^]]+)]"""), "$1")
    out = out.replace(Regex("""\n{3,}"""), "\n\n")
    return out.trim()
}
