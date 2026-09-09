package org.evvdroid

/**
 * Everything text goes through before the engine, in order, shared by the
 * speech service and the settings preview so the sample button hears what
 * TalkBack will hear.
 *
 * Emoji into words first, then the crash guards on words as typed, then
 * readability fixes. User dictionaries stay out: they live on the service
 * engine, which the preview does not share.
 */
object TextPipeline {

	/**
	 * [raw] rewritten. [stripBackquotes] guards hostile markup out.
	 */
	fun process(
		raw: String,
		emoji: Boolean,
		stripBackquotes: Boolean
	): String {
		if (raw.isEmpty()) return raw
		val worded = if (emoji) EmojiProcessing.apply(raw) else raw
		val guarded = AntiCrash.apply(worded, stripBackquotes)
		return TextFixes.apply(guarded)
	}
}
