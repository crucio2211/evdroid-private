package org.evvdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp

/** The regex dictionary's entries, reached from the settings screen.
 *
 *  Listing, adding and deleting live here rather than on the settings
 *  screen itself: entries can number in the hundreds, and a settings screen
 *  is one stop per setting while this is a list to work through.
 */
class RegexDictionaryActivity : ComponentActivity() {

	private var model: SettingsModel? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val state = SettingsModel(this).also { model = it }
		setContent {
			MaterialTheme {
				Surface {
					RegexEditorScreen(state)
				}
			}
		}
	}

	override fun onStop() {
		super.onStop()
		model?.stopSpeaking()
	}

	override fun onDestroy() {
		super.onDestroy()
		model?.close()
		model = null
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexEditorScreen(state: SettingsModel) {
	val context = LocalContext.current
	val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { state.chooseRegexDictionary(it) }
	}
	val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		val from = state.regexFile()
		if (uri != null && from != null) {
			runCatching {
				context.contentResolver.openOutputStream(uri)?.use { sink ->
					from.inputStream().use { source -> source.copyTo(sink) }
				}
			}
		}
	}
	var adding by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf<RegexDictionaries.RawEntry?>(null) }
	Scaffold(
		topBar = { TopAppBar(title = { Text(stringResource(R.string.regex_editor_title)) }) }
	) { inset ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inset)
				.verticalScroll(rememberScrollState())
				.padding(bottom = 24.dp)
		) {
			EditorActionRow(stringResource(R.string.regex_add_label)) { adding = true }
			state.regexEntries.forEach { entry ->
				EntryRow(
					text = "${entry.pattern} → ${entry.replacement}",
					deleteDescription = stringResource(
						R.string.regex_entry_delete,
						entry.pattern,
						entry.replacement
					),
					onDelete = { deleting = entry }
				)
			}
			EditorActionRow(stringResource(R.string.regex_import_label)) {
				import.launch(arrayOf("*/*"))
			}
			if (state.regexEntries.isNotEmpty()) {
				EditorActionRow(stringResource(R.string.regex_export_label)) {
					export.launch(exportName(state))
				}
			}
		}
	}
	if (adding) {
		AddPatternDialog(
			onDismiss = { adding = false },
			onSave = { pattern, replacement ->
				if (state.addRegexEntry(pattern, replacement)) {
					adding = false
					true
				} else {
					false
				}
			}
		)
	}
	deleting?.let { gone ->
		AlertDialog(
			onDismissRequest = { deleting = null },
			title = { Text(stringResource(R.string.regex_delete_title)) },
			text = { Text("${gone.pattern} → ${gone.replacement}") },
			confirmButton = {
				TextButton(onClick = {
					val at = state.regexEntries.indexOf(gone)
					if (at >= 0) state.removeRegexEntryAt(at)
					deleting = null
				}) {
					Text(stringResource(R.string.regex_delete))
				}
			},
			dismissButton = {
				TextButton(onClick = { deleting = null }) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}

/** A suggested file name for the export: whatever the dictionary is called
 *  on the settings screen, without its count. */
private fun exportName(state: SettingsModel): String {
	val shown = state.regexDictionary.substringBefore(", ").trim()
	return shown.ifEmpty { "regex.dic" }
}

/** A label and something to press, as one stop for a screen reader. */
@Composable
private fun EditorActionRow(label: String, onClick: () -> Unit) {
	Text(
		text = label,
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = label
				role = Role.Button
			}
	)
}

/** One entry as one stop: what it matches, what it says, and that pressing
 *  deletes it. */
@Composable
private fun EntryRow(text: String, deleteDescription: String, onDelete: () -> Unit) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodyLarge,
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onDelete)
			.padding(horizontal = 16.dp, vertical = 14.dp)
			.clearAndSetSemantics {
				contentDescription = deleteDescription
				role = Role.Button
			}
	)
}

@Composable
private fun AddPatternDialog(onDismiss: () -> Unit, onSave: (String, String) -> Boolean) {
	var pattern by remember { mutableStateOf("") }
	var replacement by remember { mutableStateOf("") }
	var failed by remember { mutableStateOf(false) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.regex_add_label)) },
		text = {
			Column {
				OutlinedTextField(
					value = pattern,
					onValueChange = { pattern = it; failed = false },
					label = { Text(stringResource(R.string.regex_pattern_hint)) },
					singleLine = true
				)
				OutlinedTextField(
					value = replacement,
					onValueChange = { replacement = it; failed = false },
					label = { Text(stringResource(R.string.regex_replacement_hint)) },
					singleLine = true
				)
				if (failed) {
					Text(
						stringResource(R.string.regex_invalid),
						color = MaterialTheme.colorScheme.error
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { if (!onSave(pattern, replacement)) failed = true }) {
				Text(stringResource(R.string.regex_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}
