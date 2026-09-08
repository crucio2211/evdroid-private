package org.evvdroid

/**
 * Everything text goes through before the engine, in order, shared by the
 * speech service and the settings preview so the sample button hears what
 * TalkBack will hear.
 *
 * Emoji into words and numbers regrouped first, then the crash guards on
 * words as typed, then readability fixes, then punctuation into words and
 * kept marks. Punctuation runs after the backquote strip so the pauses it
 * leaves always survive, and after the fixes so the letter-digit rule never
 * walks into its `p1 and turns it into a spoken `p 1.
 *
 * User dictionaries stay out: they live on the service engine, which the
 * preview does not share.
 */
object TextPipeline {

	/**
	 * [raw] rewritten. A null [numberMode] or [punctLevel] switches that
	 * pass off; [stripBackquotes] guards hostile markup out.
	 */
	fun process(
		raw: String,
		emoji: Boolean,
		numberMode: Int?,
		stripBackquotes: Boolean,
		punctLevel: Int?
	): String {
		if (raw.isEmpty()) return raw
		val worded = if (emoji) EmojiProcessing.apply(raw) else raw
		val numbered = if (numberMode != null) NumberProcessing.apply(worded, numberMode) else worded
		val guarded = AntiCrash.apply(numbered, stripBackquotes)
		val fixed = TextFixes.apply(guarded)
		return if (punctLevel != null) PunctuationProcessing.apply(fixed, punctLevel) else fixed
	}
}
