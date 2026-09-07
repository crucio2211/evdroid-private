package org.evvdroid

/**
 * Emoji spoken by name.
 *
 * The engine has no names for these: left alone most come out as silence
 * and some as spelling. So every emoji becomes its plain-English CLDR name
 * -- grinning face, red heart, flag Philippines -- which is ordinary words
 * for everything below. Modifiers standing alone (a stray variation
 * selector, joiner, skin tone, tag or keycap mark) are dropped rather than
 * spoken, since they say nothing by themselves.
 *
 * Matching is longest-first, so a family wins over its first person, a
 * keycap over its digit and heart on fire over a bare heart.
 *
 * This runs first of all, before numbers: the names are words, which is
 * what every pass below wants to see.
 */
object EmojiProcessing {

	fun apply(text: String): String {
		if (text.isEmpty() || !mayContain(text)) return text
		val out = StringBuilder(text.length)
		var i = 0
		while (i < text.length) {
			val hit = EmojiNames.matchAt(text, i)
			if (hit != null) {
				if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
				out.append(hit.first)
				if (hit.second >= text.length || !text[hit.second].isWhitespace()) out.append(' ')
				i = hit.second
				continue
			}
			val modifier = EmojiNames.modifierLengthAt(text, i)
			if (modifier > 0) {
				i += modifier
				continue
			}
			out.append(text[i])
			i += 1
		}
		return out.toString().trim()
	}

	/** Whether [text] holds anything this pass could change: an entry start
	 *  or a lone modifier, so plain text skips the scan without a lookup
	 *  per character. */
	private fun mayContain(text: String): Boolean {
		for (i in text.indices) {
			if (text[i] in EmojiNames.STARTERS || EmojiNames.modifierLengthAt(text, i) > 0) return true
		}
		return false
	}
}
