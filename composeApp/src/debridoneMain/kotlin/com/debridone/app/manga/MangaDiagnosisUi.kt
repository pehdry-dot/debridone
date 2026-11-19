package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.library.SourceHealth
import com.debridone.manga.net.Diagnosis
import com.debridone.manga.sources.FailureKind
import com.debridone.manga.sources.SourceProblem
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The kinds of failure a connection diagnosis can say something about. */
internal fun FailureKind.isNetwork(): Boolean = this in setOf(
    FailureKind.DNS, FailureKind.NO_ROUTE, FailureKind.TIMEOUT, FailureKind.UNAVAILABLE, FailureKind.TLS, FailureKind.HTTP, FailureKind.BROWSER_CHECK,
)

internal fun failureKind(name: String?): FailureKind? = name?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }

/** The status a source's last test gives it: "Working", or the kind of failure, never just "Failing". */
@Composable
internal fun SourceStatusPill(health: SourceHealth?) {
    when {
        health == null -> StatusPill("Not tested")
        health.ok -> StatusPill("Working · ${formatDate(health.testedAt)}", PillTone.SUCCESS)
        else -> {
            val kind = failureKind(health.kind)
            StatusPill(
                (kind?.label ?: "Failing") + " · ${formatDate(health.testedAt)}",
                if (kind != null && kind.temporary) PillTone.WARNING else PillTone.DANGER,
            )
        }
    }
}

/**
 * A source failure: what happened and what to do, in a sentence; the technical report and a
 * connection diagnosis open below it on request, in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FailureNotice(problem: SourceProblem, baseUrl: String?, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val failure = problem.failure
    val tone = if (problem.temporary) tokens.colors.warning else tokens.colors.danger
    var open by remember(problem) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.10f)).border(1.dp, tone.copy(alpha = 0.32f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (failure != null) StatusPill(failure.title, if (problem.temporary) PillTone.WARNING else PillTone.DANGER)
            failure?.host?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
        }
        Text(problem.message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failure != null && failure.detail.isNotBlank()) MangaButton(if (open) "Hide details" else "Details", { open = !open }, primary = false)
        }
        if (open && failure != null) TechnicalText(failure.detail)
        val kind = failure?.kind
        val url = baseUrl?.takeIf { it.startsWith("http") }
        if (kind != null && kind.isNetwork() && url != null) ConnectionDiagnosis(url)
    }
}

/** The server's own words, small and selectable. */
@Composable
internal fun TechnicalText(text: String) {
    val tokens = MaterialTheme.nuvio
    SelectionContainer {
        Text(
            text, style = small().copy(fontFamily = FontFamily.Monospace), color = tokens.colors.textSecondary,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(10.dp),
        )
    }
}

/**
 * "Diagnose connection": asks [url] from DebridOne and from the manga server's own Java, compares
 * this Mac's DNS with a public one, and shows step by step where it stops. Runs only when pressed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionDiagnosis(url: String) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var running by remember(url) { mutableStateOf(false) }
    var result by remember(url) { mutableStateOf<Diagnosis?>(null) }
    var problem by remember(url) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            MangaButton(
                if (result == null) "Diagnose connection" else "Diagnose again",
                {
                    running = true
                    problem = null
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.IO) { manga.services.diagnosis.diagnose(url) }
                        } catch (failure: Exception) {
                            problem = failure.message ?: "The diagnosis could not run."
                        } finally {
                            running = false
                        }
                    }
                },
                primary = false, icon = Icons.Rounded.NetworkCheck, enabled = !running,
            )
            MangaButton("Open in your browser", { openInBrowser(url) }, primary = false, icon = Icons.Rounded.OpenInNew)
            if (running) {
                CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Asking ${url.removePrefix("https://").removePrefix("http://").substringBefore('/')} from this Mac…", style = small(), color = tokens.colors.textMuted)
            }
        }
        problem?.let { Text(it, style = small(), color = tokens.colors.danger) }
        result?.let { DiagnosisView(it) }
    }
}

@Composable
private fun DiagnosisView(diagnosis: Diagnosis) {
    val tokens = MaterialTheme.nuvio
    var technical by remember(diagnosis) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(diagnosis.headline, style = MaterialTheme.typography.titleSmall, color = if (diagnosis.kind == null) tokens.colors.success else tokens.colors.textPrimary)
        Text(diagnosis.explanation, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        diagnosis.steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (step.ok) { true -> "✓"; false -> "✕"; null -> "–" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (step.ok) { true -> tokens.colors.success; false -> tokens.colors.danger; null -> tokens.colors.textMuted },
                )
                Text(step.name, style = small(), color = tokens.colors.textPrimary, modifier = Modifier.width(250.dp))
                Text(step.detail, style = small(), color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
            }
        }
        Text(
            if (technical) "Hide what each program saw" else "What each program saw",
            style = small(), color = LocalManga.current.style.accent,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { technical = !technical }.padding(vertical = 4.dp),
        )
        if (technical) {
            TechnicalText(diagnosis.reports.joinToString("\n\n") { com.debridone.manga.net.NetworkProbe.JSON.encodeToString(com.debridone.manga.net.ProbeReport.serializer(), it) })
        }
    }
}

/** Steps of a source test, each with its result; the technical report of a failed step opens on request. */
@Composable
internal fun SourceTestSteps(health: SourceHealth, baseUrl: String?) {
    val tokens = MaterialTheme.nuvio
    var technical by remember(health) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        health.steps.forEach { step ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (step.ok) "✓" else "✕", style = MaterialTheme.typography.bodyMedium, color = if (step.ok) tokens.colors.success else tokens.colors.danger)
                Column(Modifier.width(150.dp)) {
                    Text(step.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                    if (!step.ok) failureKind(step.kind)?.let { Text(it.label, style = small(), color = if (it.temporary) tokens.colors.warning else tokens.colors.danger) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.detail + (step.millis?.takeIf { step.ok }?.let { " · $it ms" } ?: ""), style = small(), color = tokens.colors.textSecondary)
                    step.technical?.let { text ->
                        Text(
                            if (technical == step.name) "Hide details" else "Details",
                            style = small(), color = LocalManga.current.style.accent,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { technical = if (technical == step.name) null else step.name }.padding(vertical = 2.dp),
                        )
                        if (technical == step.name) TechnicalText(text)
                    }
                }
            }
        }
        if (health.steps.isEmpty()) Text(health.message ?: "The test could not start.", style = small(), color = tokens.colors.textSecondary)
        val kind = failureKind(health.kind)
        val url = baseUrl?.takeIf { it.startsWith("http") }
        if (!health.ok && kind != null && kind.isNetwork() && url != null) ConnectionDiagnosis(url)
    }
}

