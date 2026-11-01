package com.girish.expensetracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** "1st", "2nd", "12th". */
internal fun dayOrdinal(d: Int) = "$d" + if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

/**
 * A day of the month, picked from a calendar-style grid instead of typed: tap 12 and it means "the 12th of every
 * month". Days past a short month's end fall on its last day.
 */
@Composable
internal fun DayOfMonthField(label: String, day: Int?, onDay: (Int?) -> Unit, hint: String, clearable: Boolean = true) {
    val t = LocalTones.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = t.ink)
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, t.edge, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Pick a day") { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                day?.let { "${dayOrdinal(it)} of every month" } ?: "Pick a day",
                style = MaterialTheme.typography.bodyLarge, color = if (day == null) t.ink3 else t.ink, modifier = Modifier.weight(1f),
            )
            Text("▾", style = MaterialTheme.typography.titleMedium, color = t.ink2)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = t.ink2, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
    if (open) DayGridDialog(day, onDismiss = { open = false }, onClear = if (clearable && day != null) ({ onDay(null); open = false }) else null) {
        onDay(it); open = false
    }
}

/** 1 to 31 laid out like a month, seven to a row. */
@Composable
internal fun DayGridDialog(selected: Int?, onDismiss: () -> Unit, onClear: (() -> Unit)? = null, onPick: (Int) -> Unit) {
    val t = LocalTones.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.panel,
        title = { Text("Day of the month", color = t.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..31).chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        week.forEach { d ->
                            val on = d == selected
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                                    .background(if (on) t.accent else t.raised)
                                    .clickable(role = Role.Button, onClickLabel = "${dayOrdinal(d)} of every month") { onPick(d) },
                                contentAlignment = Alignment.Center,
                            ) { Text("$d", style = MaterialTheme.typography.bodyMedium, color = if (on) t.ground else t.ink) }
                        }
                        repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("29–31 fall on the last day in shorter months.", style = MaterialTheme.typography.bodySmall, color = t.ink3)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = t.ink2) } },
        dismissButton = onClear?.let { { TextButton(onClick = it) { Text("Clear", color = t.ink2) } } },
    )
}
