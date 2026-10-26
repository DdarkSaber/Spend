package com.girish.expensetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.core.DetectedSource
import com.girish.expensetracker.core.Source
import com.girish.expensetracker.data.Owner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review of what the app learned from the inbox: which accounts and cards are yours, what to call you, and when your
 * salary month starts. Nothing here is hardcoded; it is detected, then confirmed or changed by the owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OwnerSheet(
    owner: Owner,
    loadSources: suspend () -> List<DetectedSource>,
    onDismiss: () -> Unit,
    onSave: (keep: Set<Source>, offered: Set<Source>, name: String, salaryDay: Int) -> Unit,
) {
    val t = LocalTones.current
    var sources by remember { mutableStateOf<List<DetectedSource>?>(null) }
    var keep by remember { mutableStateOf(owner.tracked) }
    var name by remember { mutableStateOf(owner.displayName) }
    var day by remember { mutableStateOf(owner.salaryDay) }
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { loadSources() }
        // Anything tracked by hand (a card added later) stays on the list even if it has no SMS yet.
        val extra = owner.tracked.filter { s -> found.none { it.source == s } }.map { DetectedSource(it, 0) }
        sources = found + extra
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = t.panel) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Your accounts", style = MaterialTheme.typography.headlineSmall, color = t.ink)
                Text(
                    "Found in your bank SMS. Switch off anything that isn't yours (a family member's card, an old account).",
                    style = MaterialTheme.typography.bodyMedium, color = t.ink2,
                )
            }
            val list = sources
            if (list == null) {
                item { Text("Reading your SMS…", style = MaterialTheme.typography.bodyMedium, color = t.ink3) }
            } else {
                item {
                    Panel(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        list.forEachIndexed { k, d ->
                            if (k > 0) Hairline()
                            val on = d.source in keep
                            ListRow(
                                title = sourceLabel(d.source),
                                subtitle = if (d.count > 0) count(d.count, "message") else "added by you",
                                leading = { BankMark(d.source, 36.dp) },
                                trailing = {
                                    Switch(
                                        checked = on, onCheckedChange = { keep = if (it) keep + d.source else keep - d.source },
                                        colors = SwitchDefaults.colors(checkedTrackColor = t.accent, checkedThumbColor = Color.White, uncheckedTrackColor = t.section, uncheckedBorderColor = t.edge),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            item {
                Gap(4.dp)
                Text("Your name", style = MaterialTheme.typography.titleSmall, color = t.ink)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = t.accent, unfocusedBorderColor = t.edge, focusedTextColor = t.ink, unfocusedTextColor = t.ink, cursorColor = t.accent,
                    ),
                    supportingText = { Text("Shown on Home. Transfers to this name between your own accounts are never counted as spend.") },
                )
            }
            item {
                Text("Salary month starts on", style = MaterialTheme.typography.titleSmall, color = t.ink)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Earlier day", { day = if (day <= 1) 31 else day - 1 }, size = 40.dp)
                    Text(
                        "${day}${ordinal(day)}", style = MaterialTheme.typography.headlineSmall, color = t.ink,
                        modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Later day", { day = if (day >= 31) 1 else day + 1 }, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Used when no salary SMS arrives that month.", style = MaterialTheme.typography.bodySmall, color = t.ink3, modifier = Modifier.weight(1f))
                }
            }
            item {
                Gap(6.dp)
                Button(
                    onClick = { onSave(keep, list?.map { it.source }?.toSet().orEmpty() + owner.tracked, name, day) },
                    enabled = list != null && name.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.ink, contentColor = t.ground, disabledContainerColor = t.raised, disabledContentColor = t.ink3),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Looks right", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

private fun ordinal(d: Int) = if (d in 11..13) "th" else when (d % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

/** Home card shown once after detection, until the owner confirms. */
@Composable
internal fun ReviewSetupCard(owner: Owner, onReview: () -> Unit) {
    val t = LocalTones.current
    Panel {
        Text("Set up from your SMS", style = MaterialTheme.typography.titleMedium, color = t.ink)
        Text(
            "Found ${owner.tracked.size} accounts and cards" + (if (owner.displayName.isNotBlank()) " for ${owner.displayName}" else "") +
                ", salary month from the ${owner.salaryDay}${ordinal(owner.salaryDay)}. Check that's right.",
            style = MaterialTheme.typography.bodyMedium, color = t.ink2, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Button(
            onClick = onReview, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = t.accent, contentColor = Color(0xFF04211C)),
        ) { Text("Review", style = MaterialTheme.typography.labelLarge) }
    }
}

@Suppress("unused")
private val keepColumn: @Composable () -> Unit = { Column {} }
