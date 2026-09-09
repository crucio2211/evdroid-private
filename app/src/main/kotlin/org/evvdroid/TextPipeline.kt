package org.evvdroid

/**
 * Everything text goes through before the engine, in order, shared by the
 * speech service and the settings preview so the sample button hears what
 * TalkBack will hear.
 *
 * Thousands commas go first, always, even with everything off. Then emoji
 * into words, numbers regrouped when asked, the crash guards on words as
 * typed, readability fixes, punctuation when asked, and the user's own
 * rewrites closest to the engine. User dictionaries stay out: they live
 * on the service engine, which the preview does not share.
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
		val stripped = NumberProcessing.stripThousandsCommas(raw)
		val worded = if (emoji) EmojiProcessing.apply(stripped) else stripped
		val numbered = if (numberMode != null) NumberProcessing.apply(worded, numberMode) else worded
		val guarded = AntiCrash.apply(numbered, stripBackquotes)
		val fixed = TextFixes.apply(guarded)
		return if (punctLevel != null) PunctuationProcessing.apply(fixed, punctLevel) else fixed
	}
}
