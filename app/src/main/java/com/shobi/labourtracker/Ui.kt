@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Theme: one light scheme for the whole app (readable outdoors)
// ---------------------------------------------------------------------------
@Composable
fun LabourTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF1769AA),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7E9FF),
        onPrimaryContainer = Color(0xFF001D35),
        surface = Color(0xFFF8FAFC),
        surfaceContainerLow = Color(0xFFF0F4F8),
        surfaceVariant = Color(0xFFE1E7EF)
    )
    MaterialTheme(colorScheme = colors, content = content)
}

// ---------------------------------------------------------------------------
// Status colours - the same three everywhere (dashboard, list, details)
// ---------------------------------------------------------------------------
object StatusStyle {
    // background to foreground
    fun colors(s: ProjectStatus): Pair<Color, Color> = when (s) {
        ProjectStatus.Pending -> Color(0xFFFFE9C2) to Color(0xFF7A4B00)
        ProjectStatus.Ongoing -> Color(0xFFD7E9FF) to Color(0xFF0B4A82)
        ProjectStatus.Completed -> Color(0xFFD9F2E1) to Color(0xFF14663A)
    }
}

@Composable
fun StatusChip(status: ProjectStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = StatusStyle.colors(status)
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = bg) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(fg, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(status.label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------------------
// Cards and sections
// ---------------------------------------------------------------------------
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, tonalElevation = 1.dp) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        trailing()
    }
}

// ---------------------------------------------------------------------------
// Loading / empty / message states
// ---------------------------------------------------------------------------
@Composable
fun LoadingBox(modifier: Modifier = Modifier, label: String = "Loading...") {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(16.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(actionLabel) }
        }
    }
}

enum class BannerKind { Success, Error, Info }

@Composable
fun MessageBanner(kind: BannerKind, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, icon) = when (kind) {
        BannerKind.Success -> Triple(Color(0xFFD9F2E1), Color(0xFF14663A), Icons.Filled.CheckCircle)
        BannerKind.Error -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Filled.Warning)
        BannerKind.Info -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, Icons.Filled.Info)
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = bg) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
