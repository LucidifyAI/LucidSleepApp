package com.lucidify.lucidsleep.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * "How to put on the patch": the bedtime steps of the LucidSleep™ setup video, drawn in code
 * as vector animation (no video file, a few KB in the app). Shapes come from the video:
 * the LucidPro outline is Divya's patch.svg, in the same 542 x 173 drawing units.
 */

/** Remembers whether this phone has seen the how-to all the way through once. */
object SetupGuide {
	private const val PREFS = "lucidsleep_guide"
	private const val WATCHED = "watched"

	fun watched(context: Context): Boolean =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WATCHED, false)

	fun markWatched(context: Context) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WATCHED, true).apply()
	}
}

private class GuideStep(
	val kicker: String,
	val title: String,
	val text: String,
	val seconds: Float,
	val draw: DrawScope.(Float) -> Unit
)

private val STEPS = listOf(
	GuideStep("Before bed", "Charge the patch", "Flashing white means charging. Solid white means full.", 4.0f) { drawCharge(it) },
	GuideStep("Each night", "Snap on a new sticker", "Open tonight's pouch. Press the patch onto the sticker until all three snaps click. Leave the clear cover on.", 3.6f) { drawSnap(it) },
	GuideStep("Each night", "Turn it on", "Press the center button. The white light comes on and the app can find the patch.", 3.2f) { drawTurnOn(it) },
	GuideStep("Each night", "Clean, then peel", "Wipe your forehead with the alcohol wipe and let it dry. Brush your hair aside. Then peel the clear cover off the sticker.", 5.0f) { drawWipe(it) },
	GuideStep("Each night", "Place and press", "Center the patch on your forehead, below the hairline and above the eyebrows. Press it down all around for 10 seconds.", 4.4f) { drawPlace(it) },
	GuideStep("In the app", "Wait for three greens", "The app shows the three sensors. Press the patch over any that are yellow. When all three are green, tap Start recording.", 3.6f) { drawContact(it) }
)

/** Each step's last frame stays up this long before the next step starts. */
private const val HOLD_SECONDS = 1.4f
private const val ART_W = 1000f
private const val ART_H = 720f

/**
 * Plays the steps one after another, like a short video. Tapping a dot jumps to that step.
 * [onDone] is both "Skip" and the button at the end.
 */
@Composable
fun SetupGuideScreen(doneLabel: String, onDone: () -> Unit) {
	val context = LocalContext.current
	var index by rememberSaveable { mutableIntStateOf(0) }
	var finished by rememberSaveable { mutableStateOf(false) }
	var replay by remember { mutableIntStateOf(0) }
	var t by remember { mutableFloatStateOf(0f) }
	val step = STEPS[index]

	LaunchedEffect(index, replay) {
		if (finished) {
			t = step.seconds
			return@LaunchedEffect
		}
		t = 0f
		val start = withFrameNanos { it }
		while (t < step.seconds + HOLD_SECONDS) {
			t = (withFrameNanos { it } - start) / 1_000_000_000f
		}
		if (index < STEPS.lastIndex) {
			index++
		} else {
			finished = true
			SetupGuide.markWatched(context)
		}
	}

	ScreenFrame(
		title = "How to put on the patch",
		bottom = {
			if (finished) {
				BigButton(doneLabel, onClick = onDone)
				QuietButton("Watch again") {
					finished = false
					index = 0
					replay++
				}
			} else {
				QuietButton("Skip", onClick = onDone)
			}
		}
	) {
		Box(
			Modifier
				.fillMaxWidth()
				.aspectRatio(ART_W / ART_H)
				.clip(RoundedCornerShape(20.dp))
				.background(Palette.NavyRaised)
				.semantics { contentDescription = step.title }
		) {
			Canvas(Modifier.fillMaxSize()) {
				val k = min(size.width / ART_W, size.height / ART_H)
				withTransform({
					translate((size.width - ART_W * k) / 2f, (size.height - ART_H * k) / 2f)
					scale(k, k, Offset.Zero)
				}) { step.draw(this, t) }
			}
		}
		Row(
			Modifier.fillMaxWidth().padding(vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
			verticalAlignment = Alignment.CenterVertically
		) {
			STEPS.indices.forEach { i ->
				val color = when {
					i == index -> Palette.Amber
					i < index || finished -> Palette.InkMuted
					else -> Palette.NavyLine
				}
				Box(
					Modifier
						.clip(CircleShape)
						.clickable {
							finished = false
							index = i
							replay++
						}
						.padding(6.dp)
				) {
					Box(Modifier.size(width = if (i == index) 26.dp else 12.dp, height = 12.dp).clip(CircleShape).background(color))
				}
			}
		}
		Text(
			"${step.kicker.uppercase()} · STEP ${index + 1} OF ${STEPS.size}",
			color = Palette.Amber,
			fontSize = 15.sp,
			fontWeight = FontWeight.Bold,
			letterSpacing = 1.5.sp
		)
		Text(step.title, style = MaterialTheme.typography.headlineMedium)
		Text(step.text, style = MaterialTheme.typography.bodyLarge, fontSize = 20.sp, lineHeight = 28.sp, color = Palette.Ink)
	}
}

// ---------------------------------------------------------------------------------------
// Timing helpers (t in seconds from the start of a step)
// ---------------------------------------------------------------------------------------

private const val PI_F = 3.1415927f
private fun unit01(x: Float) = x.coerceIn(0f, 1f)
private fun span(t: Float, a: Float, b: Float) = unit01((t - a) / (b - a))
private fun easeOut(x: Float) = 1f - (1f - x).pow(3)
private fun easeInOut(x: Float) = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun easeBack(x: Float, k: Float = 1.70158f): Float {
	val y = x - 1f
	return 1f + (k + 1f) * y * y * y + k * y * y
}
private fun mix(a: Float, b: Float, x: Float) = a + (b - a) * x

// ---------------------------------------------------------------------------------------
// Drawings
// ---------------------------------------------------------------------------------------

private val INK = Color(0xFFF3F1EA)
private val SKIN = Color(0xFF323C4A)
private val HAIR = Color(0xFF697077)
private val WING = Color(0xFF808080)
private val WING_EDGE = Color(0xFF5E6466)
private val SENSOR_EDGE = Color(0xFF404040)
private val PUCK = Color(0xFF8E24AA)
private val PUCK_EDGE = Color(0xFF233748)
private val LED_OFF = Color(0xFF2B2730)
private val BAND_EDGE = Color(0xFF80858A)
private val COVER = Color(0xFFD7E6F0)
private val STUD = Color(0xFFD5D8DC)
private val STUD_EDGE = Color(0xFF7F848A)
private val GEL_RIM = Color(0xFFE6E8EB)
private val GEL = Color(0xFF8E9399)
private val CABLE = Color(0xFF1C1D21)
private val CABLE_EDGE = Color(0xFF3A4A60)
private val PLUG = Color(0xFF26272B)
private val GREY = Color(0xFFB5BCC8)
private val GREEN = Color(0xFF5FCB8A)
private val YELLOW = Color(0xFFF2B544)

private val INK_LINE = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val THIN_INK = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Sensors in drawing units: middle, wearer's right (viewer's left), wearer's left. */
private val SENSORS = listOf(Offset(269f, 74f), Offset(43f, 116f), Offset(480f, 107f))
private val WHITE_LED = Offset(268f, 39f)

private object Shapes {
	private fun path(d: String) = PathParser().parsePathString(d).toPath()
	val wingL = path("M181 121C181 121 108 128 71 148C33 167 16 152 7 132C-2 112 9 88 33 77C58 66 117 48 148 44C179 40 181 121 181 121Z")
	val wingR = path("M349 114C349 114 422 121 459 141C497 161 514 145 523 125C532 106 521 81 497 70C472 59 413 42 382 37C351 33 349 114 349 114Z")
	val puck = path("M139 90C138 63 141 46 181 25C212 9 235 5 269 4C304 3 342 11 360 22C379 35 401 44 402 83C402 100 392 122 372 132C355 141 313 144 271 144C235 144 189 143 172 136C158 130 141 127 139 90Z")
	val band = path("M-14 128C-22 92 18 58 120 32C190 -6 350 -6 422 28C522 54 558 96 548 132C540 172 470 172 420 152C380 160 160 162 110 158C40 178 -8 166 -14 128Z")
	val cable = path("M114 24H60C0 24 -40 120 -140 150C-220 174 -320 200 -420 200")
	val head = path("M140 430C140 210 260 90 400 90C540 90 660 210 660 430C660 640 560 800 400 800C240 800 140 640 140 430Z")
	val hair = path("M150 360C170 200 270 120 400 120C530 120 630 200 650 360C600 250 520 196 400 196C280 196 200 250 150 360Z")
	val features = listOf(
		path("M240 452C270 432 312 430 344 444M456 444C488 430 530 432 560 452"),
		path("M262 506C282 522 316 522 336 506M464 506C484 522 518 522 538 506"),
		path("M400 520V600C400 616 388 624 372 622"),
		path("M352 680C380 694 420 694 448 680")
	)
	val fingerSkin = path("M22 300V74C22 38 38 16 60 16C82 16 98 38 98 74V300")
	val nail = path("M42 46C46 34 52 30 60 30C68 30 74 34 78 46L76 74C66 80 54 80 44 74Z")
	val knuckle = path("M30 170C48 180 72 180 90 170")
}

private inline fun DrawScope.at(origin: Offset, factor: Float, block: DrawScope.() -> Unit) =
	withTransform({
		translate(origin.x, origin.y)
		scale(factor, factor, Offset.Zero)
	}, block)

/** The LucidPro from the front, in its 542 x 173 drawing units. [rings]: a color per sensor. */
private fun DrawScope.patch(whiteOn: Boolean, alpha: Float = 1f, rings: List<Color>? = null) {
	val edge2 = Stroke(2f)
	for (wing in listOf(Shapes.wingL, Shapes.wingR)) {
		drawPath(wing, WING, alpha = alpha)
		drawPath(wing, WING_EDGE, alpha = alpha, style = edge2)
	}
	for (c in listOf(SENSORS[1], SENSORS[2])) {
		drawOval(WING, Offset(c.x - 24f, c.y - 26f), Size(48f, 52f), alpha = alpha)
		drawOval(SENSOR_EDGE, Offset(c.x - 24f, c.y - 26f), Size(48f, 52f), alpha = alpha, style = edge2)
	}
	drawPath(Shapes.puck, PUCK, alpha = alpha)
	drawPath(Shapes.puck, PUCK_EDGE, alpha = alpha, style = edge2)
	val mid = SENSORS[0]
	drawOval(PUCK, Offset(mid.x - 24f, mid.y - 26f), Size(48f, 52f), alpha = alpha)
	drawOval(PUCK_EDGE, Offset(mid.x - 24f, mid.y - 26f), Size(48f, 52f), alpha = alpha, style = edge2)
	for (led in listOf(Offset(252f, 25f), Offset(268f, 23f), Offset(285f, 24f))) drawCircle(LED_OFF, 4.5f, led, alpha = alpha)
	drawCircle(if (whiteOn) Color.White else LED_OFF, 4.5f, WHITE_LED, alpha = alpha)
	if (whiteOn) {
		drawCircle(
			Brush.radialGradient(listOf(Color.White.copy(alpha = 0.75f), Color.Transparent), center = WHITE_LED, radius = 30f),
			radius = 30f, center = WHITE_LED, alpha = alpha
		)
	}
	rings?.forEachIndexed { i, c ->
		drawCircle(c.copy(alpha = 0.35f), 32f, SENSORS[i], alpha = alpha)
		drawCircle(c, 32f, SENSORS[i], alpha = alpha, style = Stroke(7f))
	}
}

/** Tonight's sticker, front side, in the patch's drawing units. */
private fun DrawScope.sticker(alpha: Float, studs: Boolean, cover: Boolean) {
	if (cover) {
		withTransform({ scale(1.1f, 1.1f, Offset(267f, 80f)) }) {
			drawPath(Shapes.band, COVER.copy(alpha = 0.3f), alpha = alpha)
			drawPath(Shapes.band, INK.copy(alpha = 0.4f), alpha = alpha, style = Stroke(2f))
		}
	}
	drawPath(Shapes.band, Color.White, alpha = alpha)
	drawPath(Shapes.band, BAND_EDGE, alpha = alpha, style = Stroke(3f))
	if (studs) SENSORS.forEach {
		drawCircle(STUD, 15f, it, alpha = alpha)
		drawCircle(STUD_EDGE, 15f, it, alpha = alpha, style = Stroke(3f))
		drawCircle(Color(0xFFF4F5F7), 4f, it - Offset(4f, 4f), alpha = alpha)
	}
}

/** A fingertip pointing up, [tip] where it touches; drawn at [factor] of 120 x 300 units. */
private fun DrawScope.finger(tip: Offset, factor: Float) {
	withTransform({
		translate(tip.x, tip.y)
		scale(factor, factor, Offset.Zero)
		translate(-60f, -16f)
	}) {
		drawPath(Shapes.fingerSkin, SKIN)
		drawPath(Shapes.fingerSkin, INK, style = INK_LINE)
		drawPath(Shapes.nail, INK, style = THIN_INK)
		drawPath(Shapes.knuckle, INK, style = THIN_INK)
	}
}

/** A sleeper's face, eyes closed, in 800 x 900 units; forehead center about (400, 300). */
private fun DrawScope.face(alpha: Float) {
	drawPath(Shapes.head, SKIN, alpha = alpha)
	drawPath(Shapes.head, INK, alpha = alpha, style = INK_LINE)
	drawPath(Shapes.hair, HAIR, alpha = alpha)
	drawPath(Shapes.hair, INK, alpha = alpha, style = INK_LINE)
	Shapes.features.forEach { drawPath(it, INK, alpha = alpha, style = INK_LINE) }
}

// Where things sit on the 1000 x 720 stage.
private val PATCH_AT = Offset(150f, 250f)
private const val PATCH_SCALE = 1.3f
private val FACE_AT = Offset(212f, 30f)
private const val FACE_SCALE = 0.72f

private fun DrawScope.drawCharge(t: Float) {
	val plugIn = 1.2f
	val solid = 3.0f
	val p = easeInOut(span(t, 0.35f, plugIn))
	val recoil = if (t > plugIn) sin(min(1f, (t - plugIn) / 0.25f) * PI_F) * 4f else 0f
	at(PATCH_AT, PATCH_SCALE) {
		withTransform({ translate(mix(-300f, 0f, p) - recoil, 0f) }) {
			drawPath(Shapes.cable, CABLE_EDGE, style = Stroke(15f, cap = StrokeCap.Round))
			drawPath(Shapes.cable, CABLE, style = Stroke(11f, cap = StrokeCap.Round))
			drawRoundRect(PLUG, Offset(114f, 13f), Size(60f, 22f), CornerRadius(6f))
			drawRoundRect(CABLE_EDGE, Offset(114f, 13f), Size(60f, 22f), CornerRadius(6f), style = Stroke(2f))
		}
		// Flashing about once a second while charging, then solid.
		val on = t >= solid || (t > plugIn + 0.2f && floor((t - plugIn - 0.2f) / 0.45f).toInt() % 2 == 0)
		patch(on)
	}
}

private fun DrawScope.drawSnap(t: Float) {
	val land = 1.6f
	at(PATCH_AT, PATCH_SCALE) {
		sticker(alpha = easeOut(span(t, 0f, 0.4f)), studs = true, cover = true)
		// Lowered from above, it lands with a small squash.
		val fall = easeInOut(span(t, 0.4f, land))
		val squash = if (t > land) sin(min(1f, (t - land) / 0.3f) * PI_F) * 0.035f else 0f
		withTransform({
			translate(0f, mix(-330f, 0f, fall))
			scale(1f + squash * 0.5f, 1f - squash, Offset(267f, 160f))
		}) { patch(false, alpha = easeOut(span(t, 0.3f, 0.6f))) }
		// One click per snap.
		SENSORS.forEachIndexed { i, c ->
			val k = span(t, land + 0.1f + i * 0.25f, land + 0.6f + i * 0.25f)
			if (k > 0f && k < 1f) drawCircle(YELLOW, mix(20f, 52f, easeOut(k)), c, alpha = 1f - k, style = Stroke(5f))
		}
	}
}

private fun DrawScope.drawTurnOn(t: Float) {
	val tap = 1.5f
	val on = t >= tap + 0.15f
	at(PATCH_AT, PATCH_SCALE) {
		sticker(alpha = 1f, studs = false, cover = true)
		patch(on)
		val k = span(t, tap + 0.15f, tap + 0.8f)
		if (k > 0f && k < 1f) drawCircle(Color.White, mix(10f, 46f, easeOut(k)), WHITE_LED, alpha = 1f - k, style = Stroke(4f))
	}
	val button = PATCH_AT + SENSORS[0] * PATCH_SCALE
	val inP = easeOut(span(t, 0.3f, 1.1f))
	val outP = easeInOut(span(t, 2.2f, 2.9f))
	val press = sin(PI_F * unit01((t - tap + 0.12f) / 0.3f))
	if (t > 0.3f && t < 2.9f) {
		finger(button + Offset(mix(90f, 0f, inP) + outP * 100f, mix(420f, 0f, inP) + outP * 480f + press * 8f), 0.9f)
	}
}

private fun DrawScope.drawWipe(t: Float) {
	val swap = 2.7f
	val sw = easeInOut(span(t, swap, swap + 0.4f))
	if (sw < 1f) at(FACE_AT, FACE_SCALE) {
		val fa = 1f - sw
		face(fa)
		val sheen = easeOut(span(t, 0.7f, 1.6f)) * (1f - span(t, 2.0f, 2.6f)) * fa
		if (sheen > 0f) {
			drawOval(
				Brush.radialGradient(listOf(INK.copy(alpha = 0.35f), Color.Transparent), center = Offset(400f, 305f), radius = 150f),
				topLeft = Offset(250f, 230f), size = Size(300f, 150f), alpha = sheen
			)
		}
		// The wipe sweeps across the forehead and back, then lifts away.
		val a = easeInOut(span(t, 0.5f, 1.5f))
		val b = easeInOut(span(t, 1.5f, 2.3f))
		val x = mix(mix(230f, 470f, a), 300f, b)
		val y = 250f - sin(PI_F * (a + b) / 2f) * 20f
		val off = easeInOut(span(t, 2.2f, 2.6f))
		val padA = (if (t < 0.35f) easeOut(span(t, 0.15f, 0.35f)) else 1f - off) * fa
		withTransform({
			translate(x + off * 260f, y - off * 300f)
			rotate(mix(-8f, 10f, a), Offset(75f, 60f))
		}) {
			drawRoundRect(INK, Offset.Zero, Size(150f, 120f), CornerRadius(14f), alpha = padA)
			drawRoundRect(GREY, Offset.Zero, Size(150f, 120f), CornerRadius(14f), alpha = padA, style = Stroke(4f))
			drawRoundRect(
				Color(0xFF9FB8D0), Offset(18f, 18f), Size(114f, 84f), CornerRadius(8f), alpha = padA,
				style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
			)
		}
	}
	// Then the sticker, gel side up: the clear cover peels away from one end.
	if (sw > 0f) at(Offset(130f, 250f + mix(40f, 0f, sw)), 1.4f) {
		drawPath(Shapes.band, Color.White, alpha = sw)
		drawPath(Shapes.band, BAND_EDGE, alpha = sw, style = Stroke(3f))
		SENSORS.forEach {
			val c = Offset(536f - it.x, it.y)
			drawCircle(GEL_RIM, 40f, c, alpha = sw)
			drawCircle(GEL, 30f, c, alpha = sw)
		}
		val peel = easeInOut(span(t, 3.3f, 4.6f))
		val coverA = sw * (1f - span(t, 4.3f, 4.8f))
		withTransform({
			translate(mix(0f, 75f, peel), mix(0f, -160f, peel))
			rotate(mix(0f, -32f, peel), Offset(514f, 80f))
			scale(1.1f, 1.1f, Offset(267f, 80f))
		}) {
			drawPath(Shapes.band, COVER.copy(alpha = 0.45f), alpha = coverA)
			drawPath(Shapes.band, INK.copy(alpha = 0.55f), alpha = coverA, style = Stroke(3f))
		}
	}
}

private fun DrawScope.drawPlace(t: Float) {
	// The patch on the face: 0.775 of its drawing units, top left at (187, 238) of the face.
	val s = 420f / 542f
	fun onFace(p: Offset) = Offset(187f + (p.x + 6f) * s, 238f + (p.y + 6f) * s)
	// The fingertip presses the wearer's right wing, the middle, then the wearer's left wing.
	val keys = listOf(1.9f to onFace(Offset(43f, 116f)), 2.5f to onFace(Offset(269f, 96f)), 3.1f to onFace(Offset(480f, 107f)))
	at(FACE_AT, FACE_SCALE) {
		val zoom = mix(1f, 1.12f, easeInOut(span(t, 1.6f, 4.0f)))
		withTransform({ scale(zoom, zoom, Offset(400f, 300f)) }) {
			face(1f)
			val d = span(t, 0.3f, 1.4f)
			val a = easeOut(span(t, 0.3f, 0.6f))
			val origin = onFace(Offset.Zero)
			withTransform({
				translate(origin.x, origin.y + mix(-260f, 0f, easeBack(d, 1.4f)))
				scale(s, s, Offset.Zero)
				rotate(mix(-5f, 0f, easeOut(d)), Offset(267f, 80f))
			}) {
				sticker(alpha = a, studs = false, cover = false)
				patch(true, alpha = a)
			}
			var p = keys[0].second
			for (i in 1 until keys.size) {
				val m = easeInOut(span(t, keys[i - 1].first + 0.22f, keys[i].first - 0.05f))
				if (t > keys[i - 1].first) {
					p = Offset(mix(keys[i - 1].second.x, keys[i].second.x, m), mix(keys[i - 1].second.y, keys[i].second.y, m))
				}
			}
			var press = 0f
			keys.forEach { press = max(press, sin(PI_F * unit01((t - it.first + 0.12f) / 0.3f))) }
			val inP = easeOut(span(t, 1.3f, 1.85f))
			val outP = easeInOut(span(t, 3.55f, 4.2f))
			if (t > 1.3f && t < 4.2f) {
				finger(Offset(p.x + mix(120f, 0f, inP) + outP * 140f, p.y + mix(480f, 0f, inP) + outP * 640f + press * 10f), 1f)
			}
		}
	}
}

private fun DrawScope.drawContact(t: Float) {
	val good = listOf(1.6f, 2.1f, 2.6f)
	val rings = good.map { if (t < 0.9f) GREY else if (t >= it) GREEN else YELLOW }
	at(PATCH_AT, PATCH_SCALE) {
		patch(true, rings = rings)
		// A small pop as each sensor turns green.
		good.forEachIndexed { i, g ->
			val k = span(t, g, g + 0.5f)
			if (k > 0f && k < 1f) drawCircle(GREEN, mix(34f, 60f, easeOut(k)), SENSORS[i], alpha = 1f - k, style = Stroke(5f))
		}
	}
}