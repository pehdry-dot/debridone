package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.manga.library.MangaCollection
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.MetadataLink
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.library.SeriesField
import com.debridone.manga.local.LocalFiles
import com.debridone.manga.metadata.MetadataMatch
import com.debridone.manga.reader.ReaderSettings
import com.debridone.manga.sources.SourceSearchResult
import com.nuvio.app.core.ui.nuvio
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface MangaDialog {
    data class EditMetadata(val seriesId: Long) : MangaDialog
    data class MatchMetadata(val seriesId: Long) : MangaDialog
    data class Tracking(val seriesId: Long) : MangaDialog
    data class ChangeSource(val seriesId: Long) : MangaDialog
    data class Collections(val seriesIds: List<Long>) : MangaDialog
    data class RemoveSeries(val seriesIds: List<Long>) : MangaDialog
    data class SeriesReader(val seriesId: Long) : MangaDialog

    /** What became of the library of the earlier version of Manga. */
    data object Migration : MangaDialog
}

/** System file dialogs for the things Manga opens from disk. */
internal object MangaPickers {
    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** A series folder, or a folder of images. */
    fun chooseFolder(): Path? {
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            try {
                val dialog = FileDialog(null as Frame?, "Choose a manga folder", FileDialog.LOAD)
                dialog.isVisible = true
                return dialog.files.firstOrNull()?.toPath()
            } finally {
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
            }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = "Choose a manga folder"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
    }

    fun chooseArchives(): List<Path> = chooseFiles(
        "Choose comic archives", "Comic archives (CBZ, ZIP, CBR, RAR, EPUB)", arrayOf("cbz", "zip", "cbr", "rar", "epub"), multiple = true,
    )

    fun chooseImage(): Path? = chooseFiles("Choose a cover", "Images", arrayOf("jpg", "jpeg", "png", "webp"), multiple = false).firstOrNull()

    fun choosePackage(): Path? = chooseFiles("Choose an extension package", "Mihon/Tachiyomi extension packages (.apk, .jar)", arrayOf("apk", "jar"), multiple = false).firstOrNull()

    fun chooseIndexFile(): Path? = chooseFiles("Choose an index of extensions", "Extension indexes (.json)", arrayOf("json"), multiple = false).firstOrNull()

    fun chooseBackup(): Path? = chooseFiles("Choose a Manga library backup", "Suwayomi and Mihon backups (.tachibk, .proto.gz)", arrayOf("tachibk", "gz"), multiple = false).firstOrNull()

    fun saveFile(title: String, suggestedName: String): Path? {
        if (isMac) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE).apply { file = suggestedName }
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val name = dialog.file ?: return null
            return java.nio.file.Paths.get(directory, name)
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            selectedFile = java.io.File(suggestedName)
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

/** Imports files or a folder as local series and says what happened. */
internal fun importLocal(manga: MangaContext, actions: MangaActions, paths: List<Path>, type: MediaType? = null) {
    if (paths.isEmpty()) return
    actions.io {
        var imported = 0
        var lastId: Long? = null
        val problems = ArrayList<String>()
        for (path in paths) {
            try {
                lastId = manga.services.local.import(manga.profileId, path, type).id
                imported++
            } catch (failure: java.io.IOException) {
                problems += failure.message ?: "${path.fileName} could not be imported."
            }
        }
        MangaNav.message = when {
            problems.isEmpty() && imported == 1 -> "Added to your library"
            problems.isEmpty() -> "$imported series added to your library"
            imported == 0 -> problems.first()
            else -> "$imported added. ${problems.first()}"
        }
        if (imported == 1 && lastId != null) withContext(Dispatchers.Main) { MangaNav.open(MangaRoute.SeriesPage(lastId)) }
    }
}

/** Opens a web address in the person's browser. */
internal fun openInBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } else {
            MangaNav.message = "Open this address in your browser: $url"
        }
    }.onFailure { MangaNav.message = "Open this address in your browser: $url" }
}

@Composable
internal fun MangaDialogFrame(title: String, onDismiss: () -> Unit, width: Int = 480, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(width.dp)
                .heightIn(max = 720.dp)
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
internal fun MangaDialogButtons(confirm: String, onDismiss: () -> Unit, enabled: Boolean = true, danger: Boolean = false, cancel: String = "Cancel", onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
        MangaButton(cancel, onDismiss, primary = false)
        Spacer(Modifier.width(10.dp))
        MangaButton(confirm, onConfirm, enabled = enabled, primary = !danger, danger = danger)
    }
}

/** For dialogs whose changes apply as they are made: a single button that closes. */
@Composable
internal fun MangaDialogDone(onDismiss: () -> Unit, label: String = "Done") {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) { MangaButton(label, onDismiss) }
}

@Composable
internal fun MangaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    secret: Boolean = false,
    lines: Int = 1,
    hint: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else lines + 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(LocalManga.current.style.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.background)
                .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
                .onPreviewKeyEvent { event ->
                    if (onSubmit != null && lines == 1 && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        )
        if (hint != null) Text(hint, style = small(), color = tokens.colors.textMuted)
    }
}

/** Every dialog of the Manga section; shows the one [MangaNav.dialog] names. */
@Composable
internal fun MangaDialogs() {
    val close = { MangaNav.dialog = null }
    when (val dialog = MangaNav.dialog) {
        null -> Unit
        is MangaDialog.EditMetadata -> EditMetadataDialog(dialog.seriesId, close)
        is MangaDialog.MatchMetadata -> MatchMetadataDialog(dialog.seriesId, close)
        is MangaDialog.Tracking -> TrackingDialog(dialog.seriesId, close)
        is MangaDialog.ChangeSource -> ChangeSourceDialog(dialog.seriesId, close)
        is MangaDialog.Collections -> CollectionsDialog(dialog.seriesIds, close)
        is MangaDialog.RemoveSeries -> RemoveSeriesDialog(dialog.seriesIds, close)
        is MangaDialog.SeriesReader -> SeriesReaderDialog(dialog.seriesId, close)
        MangaDialog.Migration -> MigrationDialog(close)
    }
}

// ---------------------------------------------------------------------------- edit metadata

private val statuses = listOf("Ongoing", "Completed", "Hiatus", "Cancelled")

private fun splitList(text: String, separator: Regex = Regex("[,\\n]")): List<String> = text.split(separator).map(String::trim).filter(String::isNotEmpty)

/**
 * Editing a series' details by hand. Every field that is changed is locked, so a later refresh from
 * a source or a metadata provider leaves it alone; the lock on each field can also be set directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditMetadataDialog(seriesId: Long, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val tokens = MaterialTheme.nuvio
    val series by rememberMangaQuery(seriesId, initial = null as Series?) { services, _ -> services.repository.series(seriesId) }
    val original = series ?: return
    var title by remember(original.id) { mutableStateOf(original.title) }
    var altTitles by remember(original.id) { mutableStateOf(original.altTitles.joinToString("\n")) }
    var description by remember(original.id) { mutableStateOf(original.description) }
    var authors by remember(original.id) { mutableStateOf(original.authors.joinToString(", ")) }
    var artists by remember(original.id) { mutableStateOf(original.artists.joinToString(", ")) }
    var tags by remember(original.id) { mutableStateOf(original.tags.joinToString(", ")) }
    var customTags by remember(original.id) { mutableStateOf(original.customTags.joinToString(", ")) }
    var status by remember(original.id) { mutableStateOf(original.status) }
    var type by remember(original.id) { mutableStateOf(original.mediaType) }
    var coverFile by remember(original.id) { mutableStateOf<String?>(null) }
    var locks by remember(original.id) { mutableStateOf(original.lockedFields) }

    @Composable
    fun lock(field: String) {
        val locked = field in locks
        Icon(
            if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
            contentDescription = if (locked) "${SeriesField.label(field)} is locked: refreshes will not change it" else "${SeriesField.label(field)} is not locked",
            tint = if (locked) manga.style.accent else tokens.colors.textMuted,
            modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Checkbox) { locks = if (locked) locks - field else locks + field },
        )
    }

    MangaDialogFrame("Edit details", onDismiss, width = 620) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "What you change here is locked: refreshing from the source or a metadata provider will not replace it. Click a padlock to lock or unlock a field yourself.",
                style = small(), color = tokens.colors.textMuted,
            )
            MangaTextField(title, { title = it }, "Title", trailing = { lock(SeriesField.TITLE) })
            MangaTextField(altTitles, { altTitles = it }, "Alternative titles (one per line)", lines = 2, trailing = { lock(SeriesField.ALT_TITLES) })
            MangaTextField(description, { description = it }, "Synopsis", lines = 4, trailing = { lock(SeriesField.DESCRIPTION) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MangaTextField(authors, { authors = it }, "Author", Modifier.weight(1f), trailing = { lock(SeriesField.AUTHORS) })
                MangaTextField(artists, { artists = it }, "Artist", Modifier.weight(1f), trailing = { lock(SeriesField.ARTISTS) })
            }
            MangaTextField(tags, { tags = it }, "Genres (separated by commas)", lines = 2, trailing = { lock(SeriesField.TAGS) })
            MangaTextField(customTags, { customTags = it }, "Your own tags (separated by commas)", hint = "Only yours: never changed by a refresh.")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Publication status", style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
                lock(SeriesField.STATUS)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                statuses.forEach { option -> MangaChip(option, status == option) { status = option } }
                MangaChip("Unknown", status == null) { status = null }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shelf", style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
                lock(SeriesField.MEDIA_TYPE)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaType.entries.forEach { option -> MangaChip(option.label, type == option) { type = option } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Cover", style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
                lock(SeriesField.COVER)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MangaButton("Choose a picture…", { MangaPickers.chooseImage()?.let { coverFile = it.toString() } }, primary = false)
                if (original.coverFile != null || coverFile != null) MangaButton("Use the source's cover", { coverFile = "" }, primary = false)
                Text(
                    when {
                        coverFile == "" -> "The source's cover will be used."
                        coverFile != null -> java.nio.file.Paths.get(coverFile!!).fileName.toString()
                        original.coverFile != null && !original.isLocal -> "Using your own picture."
                        else -> ""
                    },
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MangaDialogButtons("Save", onDismiss, enabled = title.isNotBlank()) {
            val edit = SeriesEdit(
                title = title.trim().takeIf { it != original.title },
                altTitles = splitList(altTitles, Regex("\\n")).takeIf { it != original.altTitles },
                description = description.trim().takeIf { it != original.description },
                authors = splitList(authors).takeIf { it != original.authors },
                artists = splitList(artists).takeIf { it != original.artists },
                tags = splitList(tags).takeIf { it != original.tags },
                customTags = splitList(customTags).takeIf { it != original.customTags },
                status = status.takeIf { it != original.status },
                clearStatus = status == null && original.status != null,
                mediaType = type.takeIf { it != original.mediaType },
            )
            val chosenLocks = locks
            val cover = coverFile
            actions.io {
                manga.services.repository.editSeries(seriesId, edit, lock = true)
                if (cover != null) {
                    val stored = if (cover.isEmpty()) null else storeCover(manga, seriesId, java.nio.file.Paths.get(cover))
                    manga.services.repository.editSeries(seriesId, SeriesEdit(coverFile = stored.orEmpty()), lock = true)
                    MangaCovers.clear()
                }
                // Locks set or cleared by hand, on top of the ones the edits themselves added.
                val after = manga.services.repository.series(seriesId)?.lockedFields.orEmpty()
                val wanted = chosenLocks + edit.touchedFields() + if (cover != null) setOf(SeriesField.COVER) else emptySet()
                (wanted - after).forEach { manga.services.repository.setFieldLocked(seriesId, it, true) }
                (after - wanted).forEach { manga.services.repository.setFieldLocked(seriesId, it, false) }
                MangaNav.message = "Details saved"
            }
            onDismiss()
        }
    }
}

/** Copies a chosen picture into Manga's own folder, so the cover survives the original being moved. */
private fun storeCover(manga: MangaContext, seriesId: Long, picture: Path): String? {
    val bytes = Files.readAllBytes(picture)
    if (!com.debridone.manga.reader.MangaPages.looksLikeImage(bytes)) throw java.io.IOException("${picture.fileName} is not a picture DebridOne can show.")
    val dir = manga.services.coversDir
    Files.createDirectories(dir)
    val target = dir.resolve("custom-$seriesId-${System.currentTimeMillis()}")
    Files.write(target, bytes)
    return target.toString()
}

// ---------------------------------------------------------------------------- find details online

private fun providerName(id: String): String = when (id) {
    "anilist" -> "AniList"
    "mangadex" -> "MangaDex"
    "myanimelist" -> "MyAnimeList"
    else -> id
}

/**
 * Looks a series up on AniList, MangaDex or MyAnimeList and uses the chosen entry's details.
 * Fields the person locked keep their edits; the dialog says which fields changed.
 */
@Composable
private fun MatchMetadataDialog(seriesId: Long, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val series by rememberMangaQuery(seriesId, initial = null as Series?) { services, _ -> services.repository.series(seriesId) }
    val links by rememberMangaQuery(seriesId, initial = emptyList<MetadataLink>()) { services, _ -> services.repository.metadataLinks(seriesId) }
    val providers = remember { manga.services.metadata.enabledSources() }
    var provider by remember { mutableStateOf(providers.firstOrNull()?.id) }
    var query by remember(series?.id) { mutableStateOf(series?.title.orEmpty()) }
    var results by remember { mutableStateOf<List<MetadataMatch>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<String?>(null) }

    fun search() {
        val chosen = provider ?: return
        if (query.isBlank() || busy) return
        busy = true
        problem = null
        outcome = null
        scope.launch(Dispatchers.IO) {
            try {
                results = manga.services.metadata.search(chosen, query)
            } catch (failure: Exception) {
                problem = failure.message ?: "The search failed."
                results = null
            } finally {
                busy = false
            }
        }
    }
    fun use(match: MetadataMatch) {
        busy = true
        problem = null
        scope.launch(Dispatchers.IO) {
            try {
                val changed = manga.services.metadata.apply(seriesId, match)
                val locked = manga.services.repository.series(seriesId)?.lockedFields.orEmpty()
                MangaCovers.clear()
                outcome = (if (changed.isEmpty()) "Nothing needed changing." else "Updated: " + changed.joinToString(", ") { SeriesField.label(it).lowercase() } + ".") +
                    if (locked.isNotEmpty()) " Kept as you set them: " + locked.joinToString(", ") { SeriesField.label(it).lowercase() } + "." else ""
            } catch (failure: Exception) {
                problem = failure.message ?: "The details could not be applied."
            } finally {
                busy = false
            }
        }
    }
    fun refresh() {
        busy = true
        problem = null
        scope.launch(Dispatchers.IO) {
            try {
                val changed = manga.services.metadata.refresh(seriesId)
                MangaCovers.clear()
                outcome = if (changed.isEmpty()) "Already up to date." else "Refreshed: " + changed.joinToString(", ") { SeriesField.label(it).lowercase() } + "."
            } catch (failure: Exception) {
                problem = failure.message ?: "The refresh failed."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(series?.id, provider) { if (series != null && results == null && provider != null) search() }

    MangaDialogFrame("Find details online", onDismiss, width = 680) {
        if (providers.isEmpty()) {
            Notice("Every metadata provider is switched off. Switch one on in Plugins › Manga › Metadata.", PillTone.WARNING)
            MangaDialogButtons("Open Metadata settings", onDismiss, cancel = "Close") { onDismiss(); openMangaPlugins(MangaPluginsTab.METADATA) }
            return@MangaDialogFrame
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { source ->
                MangaChip(source.name, provider == source.id) {
                    provider = source.id
                    results = null
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaSearchField(query, { query = it }, Modifier.weight(1f), placeholder = "Title", onSubmit = ::search)
            MangaButton("Search", ::search, icon = Icons.Rounded.Search, enabled = !busy && query.isNotBlank())
        }
        if (links.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linked to " + links.joinToString(", ") { providerName(it.provider) }, style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f))
                MangaButton("Refresh from linked", ::refresh, primary = false, enabled = !busy)
            }
        }
        problem?.let { Notice(it, PillTone.DANGER) }
        outcome?.let { Notice(it, PillTone.SUCCESS) }
        Column(Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                busy && results == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) }
                results?.isEmpty() == true -> Text("${providerName(provider.orEmpty())} found nothing for \"$query\".", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                else -> results.orEmpty().forEach { match -> MatchRow(match, linked = links.any { it.provider == match.provider && it.remoteId == match.remoteId }, enabled = !busy) { use(match) } }
            }
        }
        MangaDialogDone(onDismiss)
    }
}

@Composable
private fun MatchRow(match: MetadataMatch, linked: Boolean, enabled: Boolean, onUse: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        UrlCover(match.coverUrl, Modifier.width(54.dp).aspectRatio(COVER_ASPECT), shape = RoundedCornerShape(8.dp), width = 160)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(match.title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(match.mediaType?.label, match.year?.toString(), match.status, match.chapters?.let { plural(it, "chapter") }, match.authors.firstOrNull()).joinToString("  ·  "),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (match.description.isNotBlank()) Text(match.description, style = small(), color = tokens.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            match.url?.let { url ->
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { openInBrowser(url) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("From ${providerName(match.provider)}", style = small(), color = LocalManga.current.style.accent)
                    Icon(Icons.Rounded.OpenInNew, contentDescription = "Open on ${providerName(match.provider)}", tint = LocalManga.current.style.accent, modifier = Modifier.size(12.dp))
                }
            }
        }
        MangaButton(if (linked) "Use again" else "Use", onUse, primary = !linked, enabled = enabled, icon = if (linked) Icons.Rounded.Check else null)
    }
}

// ---------------------------------------------------------------------------- tracking

// ---------------------------------------------------------------------------- change source

/**
 * The same work from another source. Each source's edition is its own series with its own
 * progress: switching never overwrites what was read. The new edition can take over the place in
 * the library and, if wanted, the chapters already read.
 */
@Composable
private fun ChangeSourceDialog(seriesId: Long, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val series by rememberMangaQuery(seriesId, initial = null as Series?) { services, _ -> services.repository.series(seriesId) }
    var query by remember(series?.id) { mutableStateOf(series?.title.orEmpty()) }
    var results by remember { mutableStateOf<List<SourceSearchResult>>(emptyList()) }
    var pending by remember { mutableStateOf(0) }
    var copyRead by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun search() {
        val current = series ?: return
        if (query.isBlank() || pending > 0) return
        results = emptyList()
        val sources = manga.services.sources.enabled(manga.profileId).filter { it.id != current.extensionId }
        pending = sources.size
        scope.launch(Dispatchers.IO) {
            try {
                manga.services.sources.search(manga.profileId, query.trim(), sources.map { it.id }) { result ->
                    results = results + result
                    pending = (pending - 1).coerceAtLeast(0)
                }
            } catch (failure: Exception) {
                MangaNav.message = failure.message ?: "The search failed."
            } finally {
                pending = 0
            }
        }
    }
    LaunchedEffect(series?.id) { if (series != null) search() }

    MangaDialogFrame("Change source", onDismiss, width = 640) {
        val current = series
        Text(
            if (current == null) "" else "Now reading from ${current.sourceName.ifBlank { "an unknown source" }}. Choose the same series in another source; what you read here stays recorded here.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaSearchField(query, { query = it }, Modifier.weight(1f), placeholder = "Title", onSubmit = ::search)
            MangaButton("Search", ::search, icon = Icons.Rounded.Search, enabled = pending == 0 && query.isNotBlank())
        }
        MangaChip("Mark the chapters I have read as read there too", copyRead) { copyRead = !copyRead }
        Column(Modifier.weight(1f, fill = false).heightIn(min = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val hits = results.filter { it.series.isNotEmpty() }
            hits.forEach { result ->
                Text(result.source.name, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textMuted, modifier = Modifier.padding(top = 6.dp))
                result.series.take(6).forEach { remote ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface)
                            .clickable(enabled = !busy, role = Role.Button) {
                                val from = current ?: return@clickable
                                busy = true
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val edition = remote
                                        manga.services.sources.refresh(edition.id)
                                        val marked = manga.services.repository.carryOver(from.id, edition.id, copyRead)
                                        MangaNav.message = "Now on ${result.source.name}" + if (marked > 0) " · ${plural(marked, "chapter")} marked read" else ""
                                        withContext(Dispatchers.Main) {
                                            onDismiss()
                                            MangaNav.replace(MangaRoute.SeriesPage(edition.id))
                                        }
                                    } catch (failure: Exception) {
                                        MangaNav.message = failure.message ?: "That edition could not be opened."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SeriesCover(remote, Modifier.width(44.dp).aspectRatio(COVER_ASPECT), shape = RoundedCornerShape(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(remote.title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(remote.status, languageName(result.source.language)).joinToString("  ·  "), style = small(), color = tokens.colors.textMuted)
                        }
                    }
                }
            }
            val failed = results.filter { it.error != null }
            if (pending > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Asking ${plural(pending, "source")}…", style = small(), color = tokens.colors.textMuted)
                }
            } else if (hits.isEmpty()) {
                Text(
                    if (manga.services.sources.enabled(manga.profileId).size <= 1) "There is no other source to look in." else "No other source has \"$query\".",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                )
            }
            if (failed.isNotEmpty() && pending == 0) {
                Text("Could not be asked: " + failed.joinToString(", ") { it.source.name }, style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        MangaDialogDone(onDismiss, "Close")
    }
}

// ---------------------------------------------------------------------------- collections, removal, reader

@Composable
private fun CollectionsDialog(seriesIds: List<Long>, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val tokens = MaterialTheme.nuvio
    val collections by rememberMangaQuery(initial = emptyList<MangaCollection>()) { services, profile -> services.repository.collections(profile) }
    val memberships by rememberMangaQuery(seriesIds, initial = emptyMap<Long, Int>()) { services, _ ->
        seriesIds.flatMap { services.repository.collectionsOf(it) }.groupingBy { it }.eachCount()
    }
    var name by remember { mutableStateOf("") }
    fun create() {
        val wanted = name.trim()
        if (wanted.isEmpty()) return
        actions.io {
            val created = manga.services.repository.createCollection(manga.profileId, wanted)
            seriesIds.forEach { manga.services.repository.setInCollection(created.id, it, true) }
        }
        name = ""
    }
    MangaDialogFrame(if (seriesIds.size == 1) "Collections" else "Collections for ${seriesIds.size} series", onDismiss, width = 440) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (collections.isEmpty()) Text("You have no collections yet. Name one below.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            collections.forEach { collection ->
                val inAll = memberships[collection.id] == seriesIds.size
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Checkbox) { actions.io { seriesIds.forEach { manga.services.repository.setInCollection(collection.id, it, !inAll) } } }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (inAll) manga.style.accent else tokens.colors.background)
                            .border(1.5.dp, if (inAll) manga.style.accent else tokens.colors.borderStrong, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (inAll) Icon(Icons.Rounded.Check, contentDescription = "In this collection", tint = manga.style.onAccent, modifier = Modifier.size(14.dp)) }
                    Text(collection.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(plural(collection.seriesCount, "series", "series"), style = small(), color = tokens.colors.textMuted)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaTextField(name, { name = it }, "New collection", Modifier.weight(1f), onSubmit = ::create)
            MangaButton("Create", ::create, primary = false, enabled = name.isNotBlank())
        }
        MangaDialogDone(onDismiss)
    }
}

@Composable
private fun RemoveSeriesDialog(seriesIds: List<Long>, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val tokens = MaterialTheme.nuvio
    val series by rememberMangaQuery(seriesIds, initial = emptyList<Series>()) { services, _ -> seriesIds.mapNotNull { services.repository.series(it) } }
    val local = series.count { it.isLocal }
    MangaDialogFrame(if (seriesIds.size == 1) "Forget \"${series.firstOrNull()?.title.orEmpty().take(40)}\"?" else "Forget ${seriesIds.size} series?", onDismiss, width = 460) {
        Text(
            "Reading progress, bookmarks and history for " + (if (seriesIds.size == 1) "this series" else "these series") + " are deleted, and so are their downloaded chapters." +
                if (local > 0) " Your own files on disk are not touched." else "",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        MangaDialogButtons("Forget", onDismiss, danger = true) {
            actions.io {
                for (id in seriesIds) {
                    val chapters = manga.services.repository.chapters(id, allLanguages = true)
                    chapters.filter { it.downloadDir != null }.forEach { chapter ->
                        runCatching { java.io.File(chapter.downloadDir!!).deleteRecursively() }
                    }
                    chapters.forEach { manga.services.pages.forgetChapter(it.id) }
                    manga.services.repository.deleteSeries(id)
                }
                MangaNav.message = if (seriesIds.size == 1) "Forgotten" else "${seriesIds.size} series forgotten"
            }
            onDismiss()
            // The page of a series that no longer exists is closed.
            if (MangaNav.current is MangaRoute.SeriesPage && (MangaNav.current as MangaRoute.SeriesPage).seriesId in seriesIds) MangaNav.back()
        }
    }
}

@Composable
private fun SeriesReaderDialog(seriesId: Long, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val series by rememberMangaQuery(seriesId, initial = null as Series?) { services, _ -> services.repository.series(seriesId) }
    val preferences by manga.services.preferences.values.collectAsState()
    val current = series ?: return
    val own = current.readerOverrides != null
    val settings = remember(current.readerOverrides, preferences) { ReaderSettings.decode(current.readerOverrides, manga.services.preferences.readerSettings(current.mediaType)) }
    Dialog(onDismissRequest = onDismiss) {
        ReaderSettingsPanel(
            settings = settings,
            shelf = current.mediaType.label,
            ownSettings = own,
            onChange = { changed ->
                if (own) actions.io { manga.services.repository.setReaderOverrides(seriesId, changed.normalized().encode()) }
                else manga.services.preferences.setReaderSettings(current.mediaType, changed)
            },
            onOwnSettings = { wanted -> actions.io { manga.services.repository.setReaderOverrides(seriesId, if (wanted) settings.encode() else null) } },
            onClose = onDismiss,
        )
    }
}
