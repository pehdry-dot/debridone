package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.debridone.app.DebridOneRuntime
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.Track
import com.debridone.music.playlists.Playlist
import com.nuvio.app.core.ui.nuvio
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Native open/save panels. On macOS these are the system panels; elsewhere Swing's chooser. */
internal object MusicPickers {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    private val audioExtensions = AudioTagReader.supportedExtensions.toTypedArray()

    fun chooseFolder(): Path? {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(null as Frame?, "Choose a music folder", FileDialog.LOAD)
                dialog.isVisible = true
                return dialog.files.firstOrNull()?.toPath()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a music folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
    }

    fun chooseAudioFiles(): List<Path> = chooseFiles("Choose music files", "Audio files", audioExtensions, multiple = true)

    fun choosePlaylistFile(): Path? = chooseFiles("Choose a playlist", "Playlists (M3U, M3U8)", arrayOf("m3u", "m3u8"), multiple = false).firstOrNull()

    fun choosePluginFile(): Path? = chooseFiles("Choose a Music plugin", "Plugin manifests (JSON, ZIP)", arrayOf("json", "zip"), multiple = false).firstOrNull()

    fun chooseImage(): Path? = chooseFiles("Choose a picture", "Images", arrayOf("jpg", "jpeg", "png"), multiple = false).firstOrNull()

    fun saveFile(title: String, suggestedName: String): Path? {
        if (isMac) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val name = dialog.file ?: return null
            return Paths.get(directory, name)
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            selectedFile = File(suggestedName)
        }
        return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
    }

    private fun chooseFiles(title: String, description: String, extensions: Array<String>, multiple: Boolean): List<Path> {
        if (isMac) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
                isMultipleMode = multiple
                setFilenameFilter { _, name -> extensions.any { name.endsWith(".$it", ignoreCase = true) } }
            }
            dialog.isVisible = true
            return dialog.files.map { it.toPath() }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            isMultiSelectionEnabled = multiple
            fileFilter = FileNameExtensionFilter(description, *extensions)
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
        return if (multiple) chooser.selectedFiles.map { it.toPath() } else listOfNotNull(chooser.selectedFile?.toPath())
    }
}

/** The actions offered for one song, used by every list. [extra] adds list-specific items. */
@Composable
internal fun TrackMenu(track: Track, dismiss: () -> Unit, extra: @Composable (() -> Unit)? = null) {
    val actions = LocalMusicActions.current
    val music = LocalMusic.current
    // Whether this song's source lets it be saved: a personal server does, an open catalogue does
    // not, and an addon does only where the listener switched that on for it.
    val savable = remember(track.id, track.sourceId) { !track.isLocal && runCatching { music.services.sources.canDownload(music.profileId, track) }.getOrDefault(false) }
    fun run(block: () -> Unit) {
        dismiss()
        block()
    }
    if (!track.missing) {
        MenuItem("Play next", Icons.Rounded.SkipNext) { run { actions.playNext(listOf(track)) } }
        MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { run { actions.addToQueue(listOf(track)) } }
    }
    MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { run { MusicNav.dialog = MusicDialog.AddToPlaylist(listOf(track.id)) } }
    MenuItem(
        if (track.favorite) "Remove from favourites" else "Add to favourites",
        if (track.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
    ) { run { actions.toggleFavorite(track) } }
    MenuDivider()
    track.albumId?.let { albumId -> MenuItem("Go to album", Icons.Rounded.Album) { run { MusicNav.open(MusicRoute.AlbumPage(albumId)) } } }
    track.artistId?.let { artistId -> MenuItem("Go to artist", Icons.Rounded.Person) { run { MusicNav.open(MusicRoute.ArtistPage(artistId)) } } }
    MenuItem("Edit details…", Icons.Rounded.Edit) { run { MusicNav.dialog = MusicDialog.EditTrack(track.id) } }
    if (track.isLocal && track.path != null && !track.missing) {
        MenuItem("Show in folder", Icons.Rounded.FolderOpen) { run { actions.revealInFileManager(track) } }
    }
    if (!track.isLocal && track.offlinePath != null) {
        MenuItem("Remove the download", Icons.Rounded.DownloadDone) { run { actions.removeDownload(track) } }
    } else if (savable && !track.missing) {
        MenuItem("Download for offline", Icons.Rounded.DownloadForOffline) { run { actions.download(listOf(track)) } }
    }
    extra?.let {
        MenuDivider()
        it()
    }
    MenuDivider()
    MenuItem("Remove from library", Icons.Rounded.Delete, danger = true) { run { MusicNav.dialog = MusicDialog.RemoveTracks(listOf(track.id), "“${track.title}”") } }
}

/** Standard row actions for a track inside the list [tracks]. */
@Composable
internal fun rememberTrackActions(track: Track, tracks: List<Track>, index: Int, extraMenu: @Composable ((dismiss: () -> Unit) -> Unit)? = null): TrackActions {
    val actions = LocalMusicActions.current
    return TrackActions(
        onPlay = { actions.play(tracks, index) },
        menu = { dismiss -> TrackMenu(track, dismiss, extra = extraMenu?.let { { it(dismiss) } }) },
        onToggleFavorite = { actions.toggleFavorite(track) },
    )
}

// ---------------------------------------------------------------------------- dialogs

@Composable
internal fun MusicDialogs() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val close = { MusicNav.dialog = null }
    when (val dialog = MusicNav.dialog) {
        null -> Unit
        MusicDialog.AddFolder -> AddFolderDialog(close)
        is MusicDialog.NewPlaylist -> TextInputDialog(
            title = "New playlist", label = "Name", initial = "", confirm = "Create", onDismiss = close,
        ) { name -> actions.createPlaylist(name, dialog.trackIds, open = dialog.trackIds.isEmpty()) }
        is MusicDialog.RenamePlaylist -> TextInputDialog(
            title = "Rename playlist", label = "Name", initial = dialog.name, confirm = "Rename", onDismiss = close,
        ) { name -> actions.io { music.services.playlists.rename(music.profileId, dialog.id, name) } }
        is MusicDialog.AddToPlaylist -> AddToPlaylistDialog(dialog.trackIds, close)
        is MusicDialog.DeletePlaylist -> ConfirmDialog(
            title = "Delete “${dialog.name}”?",
            message = "The playlist is removed. Its songs stay in your library.",
            confirm = "Delete", onDismiss = close,
        ) {
            actions.io { music.services.playlists.delete(music.profileId, dialog.id) }
            if (MusicNav.current == MusicRoute.PlaylistPage(dialog.id)) MusicNav.back()
        }
        is MusicDialog.RemoveTracks -> ConfirmDialog(
            title = "Remove ${dialog.label} from the library?",
            message = "It is also removed from your playlists. The file on disk is not deleted.",
            confirm = "Remove", onDismiss = close,
        ) { actions.removeTracks(dialog.trackIds) }
        is MusicDialog.RemoveFolder -> ConfirmDialog(
            title = "Remove this folder from the library?",
            message = "${dialog.path}\n\nIts songs leave the library and your playlists. Nothing is deleted from disk.",
            confirm = "Remove", onDismiss = close,
        ) { DebridOneRuntime.modules.music.removeFolder(dialog.id) }
        is MusicDialog.EditTrack -> TrackEditorDialog(dialog.trackId, close)
    }
}

@Composable
internal fun MusicDialogFrame(title: String, onDismiss: () -> Unit, width: Int = 460, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
internal fun DialogButtons(confirm: String, onDismiss: () -> Unit, enabled: Boolean = true, onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
        MusicButton("Cancel", onDismiss, primary = false)
        Spacer(Modifier.width(10.dp))
        MusicButton(confirm, onConfirm, enabled = enabled)
    }
}

@Composable
internal fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
    secret: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = small(), color = tokens.colors.textMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(LocalMusic.current.style.accent),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.background)
                .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                // Return confirms the dialog, as it does in every macOS sheet.
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

@Composable
private fun TextInputDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (text.isNotBlank()) {
            onDismiss()
            onConfirm(text.trim())
        }
    }
    MusicDialogFrame(title, onDismiss) {
        DialogTextField(text, { text = it }, label, focusRequester = focus, onSubmit = submit)
        DialogButtons(confirm, onDismiss, enabled = text.isNotBlank(), onConfirm = submit)
    }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    MusicDialogFrame(title, onDismiss) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        DialogButtons(confirm, onDismiss) {
            onDismiss()
            onConfirm()
        }
    }
}

/** A folder can be picked in the system panel or typed/pasted as a path (network volumes, scripts). */
@Composable
private fun AddFolderDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var path by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = submit@{
        val expanded = path.trim().let { if (it.startsWith("~")) System.getProperty("user.home") + it.drop(1) else it }
        val folder = runCatching { Paths.get(expanded) }.getOrNull()
        if (expanded.isEmpty() || folder == null || !Files.isDirectory(folder)) {
            error = "That is not a folder this computer can open."
            return@submit
        }
        onDismiss()
        DebridOneRuntime.modules.music.addFolder(folder)
        MusicNav.message = "Scanning ${folder.fileName ?: folder}…"
    }
    MusicDialogFrame("Add a music folder", onDismiss, width = 540) {
        Text(
            "DebridOne reads the songs where they are. Nothing is copied, moved or changed.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTextField(path, { path = it; error = null }, "Folder", Modifier.weight(1f), focus, submit)
            MusicButton("Browse…", {
                MusicPickers.chooseFolder()?.let {
                    path = it.toString()
                    error = null
                }
            }, primary = false, icon = Icons.Rounded.FolderOpen)
        }
        error?.let { Text(it, style = small(), color = tokens.colors.danger) }
        DialogButtons("Add folder", onDismiss, enabled = path.isNotBlank(), onConfirm = submit)
    }
}

@Composable
private fun AddToPlaylistDialog(trackIds: List<Long>, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val actions = LocalMusicActions.current
    val playlists by rememberMusicQuery(initial = emptyList<Playlist>()) { services, profile -> services.playlists.playlists(profile) }
    MusicDialogFrame("Add to playlist", onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                PickerRow("New playlist…", null, leading = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.Icon(Icons.Rounded.Add, null, tint = LocalMusic.current.style.accent) } }) {
                    MusicNav.dialog = MusicDialog.NewPlaylist(trackIds)
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                PickerRow(playlist.name, plural(playlist.trackCount, "song"), leading = { PlaylistArtwork(playlist, Modifier.size(40.dp), shape = RoundedCornerShape(6.dp)) }) {
                    onDismiss()
                    actions.addToPlaylist(playlist.id, trackIds)
                }
            }
        }
        if (playlists.isEmpty()) Text("You have no playlists yet.", style = small(), color = tokens.colors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MusicButton("Cancel", onDismiss, primary = false) }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
            if (subtitle != null) Text(subtitle, style = small(), color = tokens.colors.textMuted)
        }
    }
}
