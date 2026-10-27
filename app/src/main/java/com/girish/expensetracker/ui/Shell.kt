package com.girish.expensetracker.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.girish.expensetracker.data.Row

@Composable
internal fun SpendShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    showNav: Boolean,
    snackbar: SnackbarHostState?,
    fab: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val t = LocalTones.current
    Scaffold(
        containerColor = t.ground,
        snackbarHost = { snackbar?.let { SnackbarHost(it, Modifier.padding(bottom = 84.dp)) } },
        floatingActionButton = { fab?.invoke() },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.statusBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            content()
            if (showNav) FloatingNav(tab, onTab, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Firnhold's floating pill tab bar: glassy dark capsule, the active tab sits on a raised chip. */
@Composable
private fun FloatingNav(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    Row(
        modifier.navigationBarsPadding().padding(bottom = 12.dp)
            .clip(CircleShape).background(t.panel).border(1.dp, t.hairline, CircleShape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.filter { it.inNav }.forEach { item ->
            val selected = tab == item
            val bg by animateColorAsState(if (selected) t.raised else Color.Transparent, tween(240, easing = Ease), label = "nav")
            Column(
                Modifier.clip(CircleShape).background(bg).clickable { onTab(item) }.padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = if (selected) t.accent else t.ink3, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = if (selected) t.ink else t.ink3)
            }
        }
    }
}

@Composable
internal fun CashFab(onClick: () -> Unit) {
    val t = LocalTones.current
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        text = { Text("Cash", style = MaterialTheme.typography.labelLarge) },
        containerColor = t.ink, contentColor = t.ground, shape = RoundedCornerShape(12.dp),
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
    )
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val t = LocalTones.current
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = t.ink, contentColor = t.ground, disabledContainerColor = t.raised, disabledContentColor = t.ink3),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTones.current
    OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, t.edge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = t.ink),
        modifier = modifier.heightIn(min = 48.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
