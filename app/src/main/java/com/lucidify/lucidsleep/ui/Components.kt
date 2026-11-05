package com.lucidify.lucidsleep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
	val Navy = Color(0xFF0E1A2B)
	val NavyRaised = Color(0xFF16263C)
	val NavyLine = Color(0xFF2A3D57)
	val Ink = Color(0xFFF3F1EA)
	val InkMuted = Color(0xFFB5BCC8)
	val Amber = Color(0xFFF2B544)
	val Good = Color(0xFF5FCB8A)
	val Warn = Color(0xFFF2B544)
	val Bad = Color(0xFFF0776B)
	val Night = Color(0xFF05080D)
}

private val scheme: ColorScheme = darkColorScheme(
	primary = Palette.Amber,
	onPrimary = Palette.Navy,
	background = Palette.Navy,
	onBackground = Palette.Ink,
	surface = Palette.NavyRaised,
	onSurface = Palette.Ink,
	outline = Palette.NavyLine
)

// Large type throughout: many sleepers are 50+ and tired at bedtime.
private val type = Typography(
	headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
	headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
	titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
	bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
	bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
	labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun LucidSleepTheme(content: @Composable () -> Unit) {
	MaterialTheme(colorScheme = scheme, typography = type) {
		Surface(color = Palette.Navy, contentColor = Palette.Ink, modifier = Modifier.fillMaxSize(), content = content)
	}
}

/** One screen: title, optional subtitle, scrolling body, and the main action pinned at the bottom. */
@Composable
fun ScreenFrame(
	title: String,
	subtitle: String? = null,
	/** A small label above the title, such as "Step 2 of 5". */
	step: String? = null,
	onHelp: (() -> Unit)? = null,
	background: Color = Palette.Navy,
	bottom: @Composable ColumnScope.() -> Unit = {},
	body: @Composable ColumnScope.() -> Unit
) {
	Column(
		Modifier
			.fillMaxSize()
			.background(background)
			.statusBarsPadding()
			.navigationBarsPadding()
			.imePadding()
			.padding(horizontal = 24.dp, vertical = 16.dp)
	) {
		if (step != null) {
			Text(step.uppercase(), color = Palette.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
			Spacer(Modifier.height(4.dp))
		}
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
			if (onHelp != null) {
				TextButton(onClick = onHelp) { Text("Help", color = Palette.Amber, fontSize = 18.sp) }
			}
		}
		if (subtitle != null) {
			Spacer(Modifier.height(6.dp))
			Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Palette.InkMuted)
		}
		Spacer(Modifier.height(20.dp))
		Column(
			Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(14.dp),
			content = body
		)
		Spacer(Modifier.height(12.dp))
		Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = bottom)
	}
}

enum class HomeTab(val label: String) { HOME("Home"), NIGHTS("Nights"), HELP("Help") }

/** The tab bar under Home, My nights and Help. */
@Composable
fun HomeTabBar(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
	Column(Modifier.fillMaxWidth().background(Palette.NavyRaised).navigationBarsPadding()) {
		HorizontalDivider(color = Palette.NavyLine)
		Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
			HomeTab.entries.forEach { tab ->
				val on = tab == selected
				val color = if (on) Palette.Amber else Palette.InkMuted
				Column(
					Modifier
						.weight(1f)
						.heightIn(min = 56.dp)
						.selectable(selected = on, role = Role.Tab) { onSelect(tab) }
						.padding(vertical = 4.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
				) {
					Icon(TabIcons.of(tab), contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
					Text(tab.label, color = color, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
				}
			}
		}
	}
}

/** Line icons for the tab bar, drawn from path data (no icon library needed). */
private object TabIcons {
	private fun icon(name: String, vararg paths: String): ImageVector =
		ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
			paths.forEach {
				addPath(
					PathParser().parsePathString(it).toNodes(),
					stroke = SolidColor(Color.Black),
					strokeLineWidth = 2f,
					strokeLineCap = StrokeCap.Round,
					strokeLineJoin = StrokeJoin.Round
				)
			}
		}.build()

	private val home by lazy { icon("home", "M3 11L12 4L21 11", "M5 10V20H19V10") }
	private val moon by lazy { icon("nights", "M20 14.5A8 8 0 1 1 9.5 4A6.5 6.5 0 0 0 20 14.5Z") }
	private val help by lazy {
		icon("help", "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2Z", "M9.1 9A3 3 0 0 1 14.9 10C14.9 12 11.9 13 11.9 13", "M12 17H12.01")
	}

	fun of(tab: HomeTab): ImageVector = when (tab) {
		HomeTab.HOME -> home
		HomeTab.NIGHTS -> moon
		HomeTab.HELP -> help
	}
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
	Button(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
		shape = RoundedCornerShape(16.dp),
		colors = ButtonDefaults.buttonColors(
			containerColor = Palette.Amber,
			contentColor = Palette.Navy,
			disabledContainerColor = Palette.NavyLine,
			disabledContentColor = Palette.InkMuted
		)
	) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun QuietButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
	OutlinedButton(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
		shape = RoundedCornerShape(16.dp)
	) { Text(text, fontSize = 18.sp, color = Palette.Ink) }
}

enum class CheckState { OK, PENDING, PROBLEM }

/** A row in a checklist: a colored mark, a plain-words line, and an optional fix button. */
@Composable
fun CheckRow(
	label: String,
	state: CheckState,
	detail: String? = null,
	actionLabel: String? = null,
	onAction: (() -> Unit)? = null
) {
	Row(
		Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(14.dp))
			.background(Palette.NavyRaised)
			.padding(14.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		val (mark, color) = when (state) {
			CheckState.OK -> "✓" to Palette.Good
			CheckState.PENDING -> "…" to Palette.InkMuted
			CheckState.PROBLEM -> "!" to Palette.Bad
		}
		Box(
			Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.22f)),
			contentAlignment = Alignment.Center
		) { Text(mark, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
		Column(Modifier.weight(1f).padding(start = 14.dp)) {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = Palette.InkMuted)
		}
		if (actionLabel != null && onAction != null && state != CheckState.OK) {
			TextButton(onClick = onAction) { Text(actionLabel, color = Palette.Amber, fontSize = 17.sp) }
		}
	}
}

@Composable
fun Note(text: String, color: Color = Palette.InkMuted) {
	Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
fun Callout(text: String, color: Color = Palette.Amber) {
	Text(
		text,
		style = MaterialTheme.typography.bodyLarge,
		color = color,
		textAlign = TextAlign.Start,
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(14.dp))
			.background(color.copy(alpha = 0.12f))
			.padding(14.dp)
	)
}

fun formatDuration(ms: Long): String {
	val totalMin = (ms / 60_000L).coerceAtLeast(0L)
	val h = totalMin / 60
	val m = totalMin % 60
	return if (h > 0) "$h h $m min" else "$m min"
}