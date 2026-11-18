package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.cloud.CloudAccount
import com.debridone.cloud.CloudServices
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The application's side of the cloud layer: what the shell's cloud pages share (the services, a
 * line of feedback, work that outlives the page that started it).
 */
internal object CloudApp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val services: CloudServices? get() = DebridOneRuntime.cloud

    private val _message = MutableStateFlow<String?>(null)

    /** One line of feedback about the last thing that happened; cleared by itself. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun say(text: String?) {
        _message.value = text
    }

    /** Runs [block] off the UI thread; it goes on even if the page that started it is left. */
    fun background(block: suspend () -> Unit): Job = scope.launch {
        runCatching { block() }.onFailure { say(it.message ?: "That did not work.") }
    }

    /** Reads an account again, in the background, and says what it found. */
    fun refresh(profileId: Long?, account: CloudAccount, fresh: Boolean = true, announce: Boolean = true): Job = background {
        val cloud = services ?: return@background
        runCatching { cloud.accounts.refresh(profileId, account.id) }
        val outcome = runCatching { cloud.files.refresh(profileId, account.id, fresh) }
        if (announce) {
            say(
                outcome.fold(
                    { "${account.provider.displayName}: ${plural(it.entries, "entry").replace("entrys", "entries")}, ${plural(it.files, "file")} ready." },
                    { "${account.provider.displayName}: ${it.message}" },
                ),
            )
        }
    }
}

/** The feedback line, at the bottom of a cloud page. */
@Composable
internal fun CloudMessage() {
    val tokens = MaterialTheme.nuvio
    val message by CloudApp.message.collectAsStateWithLifecycle()
    val text = message ?: return
    LaunchedEffect(text) {
        delay(7_000)
        if (CloudApp.message.value == text) CloudApp.say(null)
    }
    Box(Modifier.fillMaxSize().padding(bottom = 96.dp, start = 40.dp, end = 40.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.colors.surfaceElevated)
                .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}
