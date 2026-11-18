package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.debridone.cloud.MediaFileClassifier
import com.debridone.cloud.ProviderId
import com.nuvio.app.core.ui.nuvio
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.text.DateFormat
import java.util.Date

@Composable
internal fun small(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)

/** A pill button in the shell's own accent: filled when [primary], outlined otherwise, red text when [danger]. */
@Composable
internal fun CloudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val background = if (primary) tokens.colors.accent else tokens.colors.surfaceElevated
    val content = when {
        danger -> tokens.colors.danger
        primary -> tokens.colors.onAccent
        else -> tokens.colors.textPrimary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(if (primary) Modifier else Modifier.border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) content else content.copy(alpha = 0.5f), modifier = Modifier.size(17.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) content else content.copy(alpha = 0.5f), maxLines = 1)
    }
}

/** A round icon-only button. [description] is what a screen reader announces. */
@Composable
internal fun CloudIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 34.dp, tint: Color? = null, enabled: Boolean = true) {
    val colour = tint ?: MaterialTheme.nuvio.colors.textSecondary
    Box(
        modifier = modifier.size(size).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) colour else colour.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.56f))
    }
}

/** A selectable pill, for filters and choices. */
@Composable
internal fun CloudChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tokens.colors.accent else tokens.colors.surfaceElevated)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * The mark of a provider: a neutral tile with its initials. The providers' own logos are their
 * trademarks and are not shipped.
 */
@Composable
internal fun ProviderMark(provider: ProviderId, size: Dp = 44.dp) {
    val tokens = MaterialTheme.nuvio
    val (initials, colour) = when (provider) {
        ProviderId.REAL_DEBRID -> "RD" to Color(0xFF2E7D5B)
        ProviderId.TORBOX -> "TB" to Color(0xFF3B6FB5)
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(colour.copy(alpha = 0.22f)).border(1.dp, colour.copy(alpha = 0.6f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp), color = tokens.colors.textPrimary)
    }
}

@Composable
internal fun CloudDialog(title: String, onDismiss: () -> Unit, width: Int = 520, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(width.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceElevated)
                .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            content()
        }
    }
}

/** A yes/no question. [danger] colours the confirming button red. */
@Composable
internal fun CloudConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, danger: Boolean = false, onConfirm: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    CloudDialog(title, onDismiss, width = 500) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            CloudButton("Cancel", onDismiss)
            CloudButton(confirm, { onDismiss(); onConfirm() }, primary = !danger, danger = danger)
        }
    }
}

@Composable
internal fun CloudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    secret: Boolean = false,
    placeholder: String? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = small(), color = tokens.colors.textMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textMuted)
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.background)
                .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onPreviewKeyEvent { event ->
                    if (onSubmit != null && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

// ---------------------------------------------------------------- words and numbers

internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatDate(millis: Long?): String = millis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—"

internal fun formatDateTime(millis: Long?): String = millis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "—"

/** "about 3 min", "about 2 h 10 min". */
internal fun formatTimeLeft(seconds: Long): String = when {
    seconds < 60 -> "less than a minute"
    seconds < 3600 -> "about ${seconds / 60} min"
    else -> "about ${seconds / 3600} h ${(seconds % 3600) / 60} min"
}

internal fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

// ---------------------------------------------------------------- this computer

/** Opens a page of a provider in the user's browser. Only https pages are ever opened. */
internal fun openInBrowser(url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(URI(url))
    }
}

internal fun copyToClipboard(text: String): Boolean = runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess

private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

/** Shows a file in the Finder (on other systems: opens its folder). Returns a message when that could not be done. */
internal fun revealInFileManager(path: Path): String? {
    if (!Files.exists(path)) return "The file is no longer at ${path}."
    return runCatching {
        when {
            // `open -R` selects the file in a Finder window; nothing is launched.
            isMac -> ProcessBuilder("/usr/bin/open", "-R", path.toAbsolutePath().toString()).start()
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) -> Desktop.getDesktop().open(path.parent.toFile())
            else -> return "This system cannot show files."
        }
        null
    }.getOrElse { "The file could not be shown (${it.message ?: it::class.simpleName})." }
}

/**
 * Opens a downloaded file with the application macOS associates with its type. A file that could
 * run (an app, an installer, a script) is never opened from here: it is shown in the Finder
 * instead, and the user decides.
 */
internal fun openWithDefaultApp(path: Path): String? {
    if (!Files.isRegularFile(path)) return "The file is no longer at ${path}."
    if (MediaFileClassifier.isExecutable(path.fileName.toString()) || Files.isExecutable(path) && MediaFileClassifier.extension(path.fileName.toString()).isEmpty()) {
        revealInFileManager(path)
        return "${path.fileName} can run as a program, so DebridOne does not open it. It is shown in the Finder instead."
    }
    return runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(File(path.toString())) else return "This system cannot open files."
        null
    }.getOrElse { "No application could open ${path.fileName}." }
}
