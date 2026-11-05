package com.lucidify.lucidsleep.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucidify.lucidsleep.AppGraph
import com.lucidify.lucidsleep.recording.NightAnswers
import com.lucidify.lucidsleep.series.SeriesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

/*
 * The sleeper's nightly questions: "About today" before the ready check and "How was last
 * night?" after Good morning. Every question can be skipped; only answered ones are saved.
 * Answers go to NightAnswers, which attaches them to the night's recording.
 */

private val DURATIONS = listOf("Under 15 min", "15–30 min", "30–60 min", "Over an hour")
private val ONE_TO_FIVE = listOf("1", "2", "3", "4", "5")

// ---------------------------------------------------------------------------------------
// Bedtime check-in
// ---------------------------------------------------------------------------------------

@Composable
fun BedtimeCheckInScreen(graph: AppGraph, onNext: () -> Unit, onBack: () -> Unit) {
	// Coming back to this screen the same evening shows what was already answered.
	val saved = remember { graph.recorder.answers.pendingBedtime() }
	var naps by rememberSaveable { mutableStateOf(saved?.text("naps")) }
	var caffeine by rememberSaveable { mutableStateOf(saved?.count("caffeineDrinks")) }
	var caffeineLast by rememberSaveable { mutableStateOf(parseClock(saved?.text("caffeineLast"))) }
	var alcohol by rememberSaveable { mutableStateOf(saved?.count("alcoholDrinks")) }
	var alcoholLast by rememberSaveable { mutableStateOf(parseClock(saved?.text("alcoholLast"))) }
	var medicine by rememberSaveable { mutableStateOf(saved?.text("sleepMedicine")) }
	var exercise by rememberSaveable { mutableStateOf(saved?.text("exercise")) }
	var stress by rememberSaveable { mutableStateOf(saved?.count("stress")?.toString()) }
	var light by rememberSaveable { mutableStateOf(saved?.text("outdoorLightMorning")) }

	fun save() {
		val j = JSONObject().put("v", 1).put("answeredAt", Instant.now().toString()).put("tz", TimeZone.getDefault().id)
		naps?.let { j.put("naps", it) }
		caffeine?.let { n ->
			j.put("caffeineDrinks", n)
			if (n > 0) caffeineLast?.let { j.put("caffeineLast", clockKey(it)) }
		}
		alcohol?.let { n ->
			j.put("alcoholDrinks", n)
			if (n > 0) alcoholLast?.let { j.put("alcoholLast", clockKey(it)) }
		}
		medicine?.let { j.put("sleepMedicine", it) }
		exercise?.let { j.put("exercise", it) }
		stress?.let { j.put("stress", it.toInt()) }
		light?.let { j.put("outdoorLightMorning", it) }
		graph.recorder.answers.saveBedtime(j)
	}

	ScreenFrame(
		title = "About today",
		subtitle = "Easier to remember now than in the morning. Skip anything you'd rather not answer.",
		bottom = {
			BigButton("Next: ready check") {
				save()
				onNext()
			}
			QuietButton("Back", onClick = onBack)
		}
	) {
		QuestionCard("Naps") {
			Choices(listOf("None", "1", "2 or more"), naps) { naps = it }
		}
		QuestionCard("Caffeine") {
			Stepper("Coffee, tea, cola or energy drinks", caffeine) { caffeine = it }
			if ((caffeine ?: 0) > 0) TimeField("Last one at", caffeineLast, { caffeineLast = it })
		}
		QuestionCard("Alcohol") {
			Stepper("Drinks", alcohol) { alcohol = it }
			if ((alcohol ?: 0) > 0) TimeField("Last one at", alcoholLast, { alcoholLast = it })
		}
		QuestionCard("Sleep medicine tonight") {
			Choices(listOf("No", "Yes"), medicine) { medicine = it }
		}
		QuestionCard("Exercise today") {
			Choices(listOf("None", "Morning", "Afternoon", "Evening"), exercise, columns = 2) { exercise = it }
		}
		QuestionCard("Stress today") {
			Choices(ONE_TO_FIVE, stress) { stress = it }
			ScaleEnds("1 = very low", "5 = very high")
		}
		QuestionCard("Outdoor light this morning") {
			Choices(listOf("No", "Yes"), light) { light = it }
		}
	}
}

// ---------------------------------------------------------------------------------------
// Morning diary
// ---------------------------------------------------------------------------------------

@Composable
fun MorningDiaryScreen(graph: AppGraph, night: SeriesStore.Night, onSaved: () -> Unit, onBack: () -> Unit) {
	val startClock = clockOf(night.startedAtEpochMs)
	val endClock = clockOf(night.endedAtEpochMs ?: System.currentTimeMillis())
	var bed by rememberSaveable { mutableIntStateOf(startClock) }
	var tried by rememberSaveable { mutableIntStateOf(startClock) }
	var woke by rememberSaveable { mutableIntStateOf(endClock) }
	var outOfBed by rememberSaveable { mutableIntStateOf(endClock) }
	var early by rememberSaveable { mutableStateOf<String?>(null) }
	var latency by rememberSaveable { mutableStateOf<String?>(null) }
	var wakes by rememberSaveable { mutableStateOf<Int?>(null) }
	var timeAwake by rememberSaveable { mutableStateOf<String?>(null) }
	var quality by rememberSaveable { mutableStateOf<String?>(null) }
	var rested by rememberSaveable { mutableStateOf<String?>(null) }
	var note by rememberSaveable { mutableStateOf("") }
	var saving by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	BackHandler(enabled = !saving, onBack = onBack)

	fun answers(): JSONObject {
		val j = JSONObject().put("v", 1).put("answeredAt", Instant.now().toString()).put("tz", TimeZone.getDefault().id)
			.put("gotIntoBed", clockKey(bed))
			.put("triedToSleep", clockKey(tried))
			.put("wokeForDay", clockKey(woke))
			.put("gotOutOfBed", clockKey(outOfBed))
			// So Cloud can tell times the sleeper changed from ones taken from Start and Stop.
			.put("recordingStarted", clockKey(startClock))
			.put("recordingStopped", clockKey(endClock))
		early?.let { j.put("wokeEarlierThanPlanned", it) }
		latency?.let { j.put("timeToFallAsleep", it) }
		wakes?.let { n ->
			j.put("awakenings", n)
			if (n > 0) timeAwake?.let { j.put("timeAwake", it) }
		}
		quality?.let { j.put("sleepQuality", it.toInt()) }
		rested?.let { j.put("rested", it.toInt()) }
		note.trim().takeIf { it.isNotEmpty() }?.let { j.put("note", it) }
		return j
	}

	ScreenFrame(
		title = "How was last night?",
		subtitle = "4 questions · about a minute. Skip any you'd rather not answer.",
		bottom = {
			BigButton(if (saving) "Saving…" else "Save", enabled = !saving) {
				saving = true
				val j = answers()
				scope.launch {
					withContext(Dispatchers.IO) { graph.recorder.answers.saveDiary(night.localSessionId, night.recordedMs, j) }
					onSaved()
				}
			}
			QuietButton("Back", enabled = !saving, onClick = onBack)
		}
	) {
		QuestionCard("1 · Check your times") {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				TimeField("Got into bed", bed, { bed = it }, Modifier.weight(1f))
				TimeField("Tried to sleep", tried, { tried = it }, Modifier.weight(1f))
			}
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				TimeField("Woke for the day", woke, { woke = it }, Modifier.weight(1f))
				TimeField("Got out of bed", outOfBed, { outOfBed = it }, Modifier.weight(1f))
			}
			Note("Filled in from when you started and stopped recording. Change any that are off.")
			Text("Woke earlier than planned?", fontSize = 18.sp)
			Choices(listOf("No", "Yes"), early) { early = it }
		}
		QuestionCard("2 · How long to fall asleep?") {
			Choices(DURATIONS, latency, columns = 2) { latency = it }
		}
		QuestionCard("3 · Waking in the night") {
			Stepper("Times you woke up", wakes) { wakes = it }
			if ((wakes ?: 0) > 0) {
				Text("Total time awake", fontSize = 18.sp)
				Choices(DURATIONS, timeAwake, columns = 2) { timeAwake = it }
			}
		}
		QuestionCard("4 · How do you feel?") {
			Text("Sleep quality", fontSize = 18.sp)
			Choices(ONE_TO_FIVE, quality) { quality = it }
			Text("How rested", fontSize = 18.sp)
			Choices(ONE_TO_FIVE, rested) { rested = it }
			ScaleEnds("1 = poor", "5 = very good")
		}
		Text("Anything else about last night? (optional)", style = MaterialTheme.typography.titleLarge)
		OutlinedTextField(
			value = note,
			onValueChange = { note = it.take(NightAnswers.MAX_NOTE_CHARS) },
			modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
			placeholder = { Text("For example, the dog woke me around 4", fontSize = 17.sp) },
			textStyle = TextStyle(fontSize = 18.sp, color = Palette.Ink)
		)
		Note("To speak instead of typing, tap the microphone on your keyboard.")
	}
}

// ---------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------

@Composable
private fun QuestionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
	Column(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.NavyRaised).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(title, style = MaterialTheme.typography.titleLarge)
		content()
	}
}

/** One choice of several; tapping the chosen one again clears it. */
@Composable
private fun Choices(options: List<String>, selected: String?, columns: Int = options.size, onSelect: (String?) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		options.chunked(columns).forEach { row ->
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				row.forEach { option ->
					val on = option == selected
					val shape = RoundedCornerShape(12.dp)
					Box(
						Modifier
							.weight(1f)
							.heightIn(min = 52.dp)
							.clip(shape)
							.background(if (on) Palette.Amber.copy(alpha = 0.18f) else Palette.Navy)
							.border(if (on) 2.dp else 1.dp, if (on) Palette.Amber else Palette.NavyLine, shape)
							.selectable(selected = on, role = Role.RadioButton) { onSelect(if (on) null else option) }
							.padding(horizontal = 6.dp, vertical = 10.dp),
						contentAlignment = Alignment.Center
					) {
						Text(
							option, fontSize = 17.sp, textAlign = TextAlign.Center, color = Palette.Ink,
							fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
						)
					}
				}
				repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
			}
		}
	}
}

/** A count with − and +. Null until the sleeper touches it (shown as 0). */
@Composable
private fun Stepper(label: String, value: Int?, max: Int = 20, onChange: (Int) -> Unit) {
	val n = value ?: 0
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(label, fontSize = 18.sp, modifier = Modifier.weight(1f))
		RoundButton("−", "One fewer", enabled = n > 0) { onChange(n - 1) }
		Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
		RoundButton("+", "One more", enabled = n < max) { onChange(n + 1) }
	}
}

@Composable
private fun RoundButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
	OutlinedButton(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.size(52.dp).semantics { contentDescription = description },
		shape = CircleShape,
		contentPadding = PaddingValues(0.dp)
	) { Text(symbol, fontSize = 24.sp, color = Palette.Ink) }
}

/** A clock time, picked with the phone's own time picker. [minutes] is minutes after midnight. */
@Composable
private fun TimeField(label: String, minutes: Int?, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
	val context = LocalContext.current
	Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, fontSize = 15.sp, color = Palette.InkMuted)
		OutlinedButton(
			onClick = {
				val m = minutes ?: (12 * 60)
				TimePickerDialog(context, { _, h, min -> onChange(h * 60 + min) }, m / 60, m % 60,
					android.text.format.DateFormat.is24HourFormat(context)).show()
			},
			modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
			shape = RoundedCornerShape(12.dp)
		) { Text(minutes?.let { clockText(context, it) } ?: "Set time", fontSize = 18.sp, color = Palette.Ink) }
	}
}

@Composable
private fun ScaleEnds(low: String, high: String) {
	Row(Modifier.fillMaxWidth()) {
		Text(low, fontSize = 15.sp, color = Palette.InkMuted, modifier = Modifier.weight(1f))
		Text(high, fontSize = 15.sp, color = Palette.InkMuted)
	}
}

private fun clockOf(epochMs: Long): Int {
	val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
	return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

private fun clockText(context: Context, minutes: Int): String {
	val cal = Calendar.getInstance().apply {
		set(Calendar.HOUR_OF_DAY, minutes / 60)
		set(Calendar.MINUTE, minutes % 60)
	}
	return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
}

/** "22:41": how clock times are saved. */
private fun clockKey(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun parseClock(text: String?): Int? {
	val parts = text?.split(":") ?: return null
	val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
	val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
	return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

private fun JSONObject.text(name: String): String? = if (has(name) && !isNull(name)) optString(name).ifBlank { null } else null
private fun JSONObject.count(name: String): Int? = if (has(name) && !isNull(name)) optInt(name) else null