package dev.pocket.aws.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max

val Shape = RoundedCornerShape(18.dp)
val SmallShape = RoundedCornerShape(12.dp)

@Composable
fun Surface(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(Shape)
            .background(P.surface)
            .border(BorderStroke(1.dp, P.border), Shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(pad),
        content = content,
    )
}

@Composable
fun Header(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = P.text) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = if (onBack != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, color = P.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = P.muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Pill(text: String, color: Color = P.muted, filled: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = if (filled) P.onAccent else color, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (filled) color else color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Bar(fraction: Float, color: Color = P.accent, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(450), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(P.surface2)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(CircleShape).background(color))
    }
}

@Composable
fun Spark(values: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float = 1f) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val n = 60
        val step = size.width / (n - 1)
        val pts = values.takeLast(n)
        val off = n - pts.size
        val top = max(max, pts.maxOrNull() ?: 0f).coerceAtLeast(0.0001f)
        val line = Path()
        pts.forEachIndexed { i, v ->
            val x = (off + i) * step
            val y = size.height - (v / top) * size.height * 0.92f
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(off * step, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = pts.last()
        drawCircle(color, 3.dp.toPx(), Offset(size.width, size.height - (last / top) * size.height * 0.92f))
    }
}

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null,
    mono: Boolean = false, singleLine: Boolean = true, minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false, trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it, color = P.faint) } },
        singleLine = singleLine, minLines = minLines, maxLines = if (singleLine) 1 else 8, shape = SmallShape,
        textStyle = if (mono) TextStyle(fontFamily = Mono, fontSize = MaterialTheme.typography.bodySmall.fontSize, color = P.text) else MaterialTheme.typography.bodyLarge.copy(color = P.text),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = P.accent, unfocusedBorderColor = P.border, focusedLabelColor = P.accent, unfocusedLabelColor = P.muted,
            focusedContainerColor = P.surface, unfocusedContainerColor = P.surface, cursorColor = P.accent,
        ),
    )
}

@Composable
fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null, color: Color = P.accent) {
    Button(
        onClick, modifier.height(50.dp), enabled = enabled && !busy, shape = SmallShape,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = P.onAccent, disabledContainerColor = P.surface2, disabledContentColor = P.faint),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = P.muted)
        else if (icon != null) Icon(icon, null, Modifier.size(18.dp))
        if (busy || icon != null) Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Secondary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = P.text, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.height(44.dp), shape = SmallShape, enabled = enabled, border = BorderStroke(1.dp, P.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color), contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Tile(icon: ImageVector, tint: Color = P.accent, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(icon, P.muted, 52.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, color = P.text)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = P.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopCenter) {
    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = P.muted)
}

@Composable
fun ErrorNote(text: String) {
    Row(Modifier.fillMaxWidth().clip(SmallShape).background(P.bad.copy(alpha = 0.1f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(P.bad); Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = P.bad)
    }
}

// ------------------------------------------------------------------ formatting

fun bytes(b: Long): String {
    if (b < 1024) return "$b B"
    val u = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (v >= 100) "%.0f %s".format(v, u[i]) else "%.1f %s".format(v, u[i])
}

fun dur(seconds: Double): String {
    val s = seconds.toLong()
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 86400}d ${(s % 86400) / 3600}h"
    }
}

fun pct(f: Double) = "${(f * 100).toInt()}%"
