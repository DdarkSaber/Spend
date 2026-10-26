package com.girish.expensetracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.core.SourceType

val PanelShape = RoundedCornerShape(24.dp)
val TileShape = RoundedCornerShape(10.dp)

/** The house ease (Firnhold): fast start, long soft landing. */
val Ease = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** A flat surface one tone above the ground with a hairline edge: the only container in the app. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(18.dp), content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTones.current
    Column(
        modifier.fillMaxWidth().clip(PanelShape).background(t.panel).border(1.dp, t.hairline, PanelShape).padding(padding),
        content = content,
    )
}

/** Card heading (Coinly): title with a chevron, optional data label, optional round icon actions on the right. */
@Composable
fun PanelHeader(
    title: String,
    label: String? = null,
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val t = LocalTones.current
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)), color = t.ink)
        if (chevron) Text("  ›", style = MaterialTheme.typography.titleMedium, color = t.ink2)
        Spacer(Modifier.weight(1f))
        if (label != null) Text(label.uppercase(), style = DataLabel, color = t.ink3)
        if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** Round icon button on a raised tone (Coinly card actions, Firnhold header pill). */
@Composable
fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, size: Dp = 40.dp) {
    val t = LocalTones.current
    Box(
        Modifier.size(size).clip(CircleShape).background(if (active) t.accentSoft else t.raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = if (active) t.accent else t.ink, modifier = Modifier.size(20.dp))
    }
}

/** Screen title block: big bold heading (both references lead with one), optional one-line subtitle. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val t = LocalTones.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700)), color = t.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** A figure that rolls to its new value (the app's one recurring motion for numbers). */
@Composable
fun RollingAmount(paise: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = rupees(paise),
        transitionSpec = {
            (slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(320))) togetherWith
                (slideOutVertically(tween(240)) { -it / 2 } + fadeOut(tween(200)))
        },
        label = "amount",
        modifier = modifier,
    ) { text -> Text(text, style = style, color = color, maxLines = 1) }
}

/** Value-first stat: the number leads, its label sits underneath in quiet ink. */
@Composable
fun StatTile(value: Long, label: String, modifier: Modifier = Modifier, note: String? = null, noteColor: Color? = null) {
    val t = LocalTones.current
    Column(modifier.clip(PanelShape).background(t.panel).border(1.dp, t.hairline, PanelShape).padding(16.dp)) {
        RollingAmount(value, MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), t.ink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(top = 2.dp))
        if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = noteColor ?: t.ink3, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Two- or three-way switch drawn as a tone-stepped track (the Dovetail tab control). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(t.raised).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val bg by animateColorAsState(if (i == selected) t.ink else Color.Transparent, tween(260, easing = Ease), label = "seg")
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape).background(bg).clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (i == selected) t.ground else t.ink2)
            }
        }
    }
}

/** Monogram for a bank: card vs account read at a glance from the shape. */
@Composable
fun BankMark(src: Source, size: Dp = 40.dp) {
    val t = LocalTones.current
    val shape = if (src.type == SourceType.CARD) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier.size(size).clip(shape).background(t.raised).border(1.dp, t.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(src.bank.take(4), style = DataLabel, color = t.ink, textAlign = TextAlign.Center)
    }
}

/** Initials avatar for people. */
@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val t = LocalTones.current
    val initials = name.replace(Regex("^(Mr|Mrs|Ms|Dr)\\.?\\s+", RegexOption.IGNORE_CASE), "")
        .split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(t.raised), contentAlignment = Alignment.Center) {
        Text(initials, style = MaterialTheme.typography.labelLarge, color = t.ink)
    }
}

/** A tappable list row inside a panel: leading slot, two lines of text, trailing slot. */
@Composable
fun ListRow(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalTones.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = t.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun Hairline() = HorizontalDivider(color = LocalTones.current.hairline, thickness = 1.dp)

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

/** Small tag (NEW, BETA-style): mono on the soft accent. */
@Composable
fun Tag(text: String) {
    val t = LocalTones.current
    Text(
        text.uppercase(), style = DataLabel, color = t.accent,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(t.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A coloured category key: small square + name. */
@Composable
fun Swatch(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(3.dp)).background(color))
}

@Suppress("unused")
private val keepFill = Modifier.fillMaxWidth()
