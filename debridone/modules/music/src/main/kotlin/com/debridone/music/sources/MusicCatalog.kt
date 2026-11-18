package com.debridone.music.sources

/** An artist as a source's catalogue lists it. */
data class RemoteArtist(
    val remoteId: String,
    val name: String,
    val artworkKey: String? = null,
    val artworkUrl: String? = null,
    val artworkAlternates: List<String> = emptyList(),
    val trackCount: Int? = null,
    val albumCount: Int? = null,
    val followerCount: Int? = null,
    val verified: Boolean = false,
    val description: String? = null,
)

data class RemoteAlbum(
    val remoteId: String,
    val title: String,
    val artist: String?,
    val year: Int? = null,
    val trackCount: Int? = null,
    val durationMs: Long? = null,
    val artworkKey: String? = null,
    val artworkUrl: String? = null,
    val artworkAlternates: List<String> = emptyList(),
)

data class RemotePlaylist(
    val remoteId: String,
    val name: String,
    val owner: String? = null,
    val trackCount: Int? = null,
    val description: String? = null,
    val artworkKey: String? = null,
    val artworkUrl: String? = null,
    val artworkAlternates: List<String> = emptyList(),
)

data class CatalogResults(
    val tracks: List<RemoteTrack> = emptyList(),
    val artists: List<RemoteArtist> = emptyList(),
    val albums: List<RemoteAlbum> = emptyList(),
    val playlists: List<RemotePlaylist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

/** Which parts of [MusicCatalogAdapter] a source really offers. Screens show only these. */
data class CatalogCapabilities(
    val searchTracks: Boolean = false,
    val searchArtists: Boolean = false,
    val searchAlbums: Boolean = false,
    val searchPlaylists: Boolean = false,
    val featured: Boolean = false,
    val genres: Boolean = false,
    /** The playlists the signed-in user keeps on the source. */
    val ownPlaylists: Boolean = false,
)

/**
 * A source whose catalogue can be searched and browsed without copying it into the library.
 *
 * Results are descriptions only. A song becomes playable in DebridOne through
 * [MusicSourceManager.materialize], and still plays from the source: nothing is downloaded.
 */
interface MusicCatalogAdapter : MusicSourceAdapter {
    val capabilities: CatalogCapabilities

    /** What one connection offers, for sources that differ from one connection to the next (addons). */
    fun capabilities(connection: SourceConnection): CatalogCapabilities = capabilities

    fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults

    /** Songs the source puts forward (trending, random, newest), optionally of one [genre]. */
    fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> = emptyList()

    fun genres(connection: SourceConnection, secret: String): List<String> = emptyList()

    fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> = emptyList()

    fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> = emptyList()

    fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> = emptyList()

    fun ownPlaylists(connection: SourceConnection, secret: String): List<RemotePlaylist> = emptyList()
}
