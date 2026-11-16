package com.debridone.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.Track
import com.debridone.music.library.TrackField
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val editorFields = listOf(
    TrackField.TITLE to "Title",
    TrackField.ARTIST to "Artist",
    TrackField.ALBUM to "Album",
    TrackField.ALBUM_ARTIST to "Album artist",
    TrackField.GENRE to "Genre",
    TrackField.COMPOSER to "Composer",
    TrackField.YEAR to "Year",
    TrackField.TRACK_NO to "Track",
    TrackField.TRACK_TOTAL to "of",
    TrackField.DISC_NO to "Disc",
    TrackField.DISC_TOTAL to "of",
)

private fun Track.fieldValue(field: String): String = when (field) {
    TrackField.TITLE -> title
    TrackField.ARTIST -> artist
    TrackField.ALBUM -> album
    TrackField.ALBUM_ARTIST -> albumArtist
    TrackField.GENRE -> genre
    TrackField.COMPOSER -> composer
    TrackField.YEAR -> year?.toString()
    TrackField.TRACK_NO -> trackNo?.toString()
    TrackField.TRACK_TOTAL -> trackTotal?.toString()
    TrackField.DISC_NO -> discNo?.toString()
    TrackField.DISC_TOTAL -> discTotal?.toString()
    else -> null
}.orEmpty()

/**
 * Edits how one song is listed. Changes are stored in DebridOne's library, not written into the
 * file. A field that was edited is locked: rescans and online lookups leave it alone until it is
 * unlocked or the original is restored.
 */
@Composable
internal fun TrackEditorDialog(trackId: Long, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val library = music.services.library
    val loaded by rememberMusicQuery(trackId, initial = null as Pair<Track?, Map<String, String?>>?) { services, profile ->
        services.library.track(profile, trackId) to services.library.originalFields(profile, trackId).orEmpty()
    }
    val values = remember(trackId) { mutableStateMapOf<String, String>() }
    val locks = remember(trackId) { mutableStateMapOf<String, Boolean>() }
    var initialised by remember(trackId) { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val (track, original) = loaded ?: return
    if (track == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    LaunchedEffect(track.id, initialised) {
        if (!initialised) {
            editorFields.forEach { (field, _) ->
                values[field] = track.fieldValue(field)
                locks[field] = field in track.lockedFields
            }
            initialised = true
        }
    }
    MusicDialogFrame("Edit details", onDismiss, width = 640) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(track.artwork, Modifier.size(96.dp), shape = RoundedCornerShape(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MusicButton("Choose a cover…", {
                            MusicPickers.chooseImage()?.let { image ->
                                actions.io {
                                    val id = library.artwork.storeFile(music.profileId, image, ArtworkStore.ORIGIN_CUSTOM)
                                    if (id == null) MusicNav.message = "That picture could not be read." else library.setTrackArtwork(music.profileId, track.id, id)
                                }
                            }
                        }, icon = Icons.Rounded.Image, primary = false)
                        if (TrackField.ARTWORK in track.lockedFields) {
                            MusicButton("Unlock cover", { actions.io { library.setFieldLocked(music.profileId, track.id, TrackField.ARTWORK, false) } }, icon = Icons.Rounded.HideImage, primary = false)
                        }
                    }
                    Text(
                        listOfNotNull(
                            track.format, track.sampleRate?.let { "%.1f kHz".format(it / 1000.0) }, track.bitDepth?.let { "$it-bit" },
                            track.bitrateKbps?.let { "$it kbps" }, formatDuration(track.durationMs),
                        ).joinToString(" · "),
                        style = small(), color = tokens.colors.textMuted,
                    )
                    Text(track.path.orEmpty(), style = small(), color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                }
            }

            TrackEditorExtras(track, onDismiss)

            @Composable
            fun field(key: String, label: String, modifier: Modifier) {
                val locked = locks[key] == true
                val fileValue = original[key].orEmpty()
                Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DialogTextField(values[key].orEmpty(), {
                            values[key] = it
                            // What you type is yours: it is locked unless you unlock it again.
                            locks[key] = true
                        }, label, Modifier.weight(1f))
                        MusicIconButton(
                            if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            if (locked) "$label is locked: rescans and lookups will not change it. Click to unlock." else "$label is not locked. Click to lock it.",
                            { locks[key] = !locked }, size = 30.dp,
                            tint = if (locked) music.style.accent else tokens.colors.textMuted,
                        )
                    }
                    if (fileValue != values[key].orEmpty()) {
                        Text("File: ${fileValue.ifEmpty { "(empty)" }}", style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            field(TrackField.TITLE, "Title", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(TrackField.ARTIST, "Artist", Modifier.weight(1f))
                field(TrackField.ALBUM_ARTIST, "Album artist", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(TrackField.ALBUM, "Album", Modifier.weight(1.6f))
                field(TrackField.YEAR, "Year", Modifier.weight(0.7f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(TrackField.GENRE, "Genre", Modifier.weight(1f))
                field(TrackField.COMPOSER, "Composer", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                field(TrackField.TRACK_NO, "Track", Modifier.weight(1f))
                field(TrackField.TRACK_TOTAL, "of", Modifier.weight(1f))
                field(TrackField.DISC_NO, "Disc", Modifier.weight(1f))
                field(TrackField.DISC_TOTAL, "of", Modifier.weight(1f))
            }
            Text(
                "Changes are kept in DebridOne's library; the file itself is not rewritten. Edited fields are locked so a rescan or an online lookup cannot overwrite them.",
                style = small(), color = tokens.colors.textMuted,
            )
        }

        if (confirmRestore) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Discard every edit and lock on this song?", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
                MusicButton("Keep my edits", { confirmRestore = false }, primary = false)
                MusicButton("Restore original", {
                    onDismiss()
                    actions.io {
                        library.restoreOriginal(music.profileId, track.id)
                        MusicNav.message = "Restored what the file says"
                    }
                })
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicButton("Restore original", { confirmRestore = true }, icon = Icons.Rounded.Restore, primary = false, enabled = track.lockedFields.isNotEmpty())
                Box(Modifier.weight(1f))
                MusicButton("Cancel", onDismiss, primary = false)
                Box(Modifier.size(10.dp))
                MusicButton("Save", {
                    val changes = editorFields.map { it.first }.filter { values[it].orEmpty().trim() != track.fieldValue(it) }.associateWith { values[it]?.trim() }
                    val lockChanges = editorFields.map { it.first }.filter { it !in changes && (locks[it] == true) != (it in track.lockedFields) }
                    onDismiss()
                    actions.io {
                        withContext(Dispatchers.IO) {
                            if (changes.isNotEmpty()) library.editTrack(music.profileId, track.id, changes)
                            // An edit locks its field; honour the lock button if it was switched off afterwards.
                            changes.keys.filter { locks[it] == false }.forEach { library.setFieldLocked(music.profileId, track.id, it, false) }
                            lockChanges.forEach { library.setFieldLocked(music.profileId, track.id, it, locks[it] == true) }
                        }
                        if (changes.isNotEmpty()) MusicNav.message = "Saved"
                    }
                }, enabled = values[TrackField.TITLE].orEmpty().isNotBlank())
            }
        }
    }
}

/** Extra tools shown in the editor (online lookup, lyrics). Filled in by the metadata screens. */
@Composable
internal fun TrackEditorExtras(track: Track, onDismiss: () -> Unit) {
    MusicMetadataTools(track, onDismiss)
}
