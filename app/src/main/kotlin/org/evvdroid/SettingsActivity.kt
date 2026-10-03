package org.evvdroid

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** The engine's own settings, reached from the system's text-to-speech screen
 *  and from the launcher. */
class SettingsActivity : ComponentActivity() {

	private var model: SettingsModel? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		CrashLog.install(this)
		// A previous run died: show what it recorded (plain views, no
		// Compose) instead of crashing again before anything is readable.
		if (CrashLog.has(this) && savedInstanceState == null) {
			showCrashReport(CrashLog.read(this))
			return
		}
		try {
			startSettings()
		} catch (failed: Throwable) {
			CrashLog.write(this, Thread.currentThread(), failed)
			showCrashReport(CrashLog.read(this))
		}
	}

	private fun startSettings() {
		val state = SettingsModel(this).also { model = it }
		setContent {
			EvvdroidTheme {
				SettingsScreen(state)
			}
		}
	}

	/** Plain-text crash report with a retry button. TalkBack reads the text;
	 *  paste it back so the crash can be fixed. */
	private fun showCrashReport(report: String) {
		val pad = (16 * resources.displayMetrics.density).toInt()
		val text = TextView(this).apply {
			text = report.ifEmpty { "The settings screen crashed." }
			textIsSelectable = true
			setPadding(pad, pad, pad, pad)
		}
		val retry = Button(this).apply {
			text = "Clear report and retry"
			setOnClickListener {
				CrashLog.clear(this@SettingsActivity)
				recreate()
			}
		}
		val box = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			addView(
				retry,
				ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT
				)
			)
			addView(
				ScrollView(this@SettingsActivity).apply { addView(text) },
				ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.MATCH_PARENT
				)
			)
		}
		setContentView(box)
	}

	override fun onStop() {
		super.onStop()
		// Leaving the screen should not leave a sample talking over whatever
		// comes next.
		model?.stopSpeaking()
	}

	override fun onResume() {
		super.onResume()
		// The regex editor is its own screen with its own model, so coming
		// back from it must re-read what it may have changed.
		model?.refreshRegexEntries()
	}

	override fun onDestroy() {
		super.onDestroy()
		model?.close()
		model = null
	}
}
