package com.debridone.app.shell

import androidx.compose.runtime.Composable
import com.debridone.app.cloud.CloudDownloadsScreen

/**
 * The Downloads section: the files of the connected accounts, the transfers to this computer and
 * what is on this computer. The screen itself lives with the rest of the cloud pages.
 */
@Composable
internal fun DownloadsScreen() = CloudDownloadsScreen()
