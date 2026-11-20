package com.droidamp.player.domain.model

enum class StorageBackend {
  SAF,
  MEDIA_STORE,
}

enum class SheetMode {
  PLAYLIST,
  LIBRARY,
}

enum class LibraryTab {
  FOLDERS,
  PLAYLISTS,
}

enum class LibraryViewLevel {
  ROOT,
  FOLDERS,
  PLAYLISTS,
  SKINS,
}

enum class PlaylistExportFormat(val extension: String, val mimeType: String) {
  M3U8(extension = "m3u8", mimeType = "audio/x-mpegurl"),
  PLS(extension = "pls", mimeType = "audio/x-scpls"),
}

enum class PlaylistSortOrder {
  TITLE,
  FILENAME,
  PATH,
}

data class FolderEntry(
  val uriString: String,
  val name: String,
  val pathSegments: List<String>,
  val trackCount: Int,
  val subfolderCount: Int,
)

data class SavedPlaylistEntry(
  val id: String,
  val uriString: String,
  val name: String,
  val format: String,
  val trackCount: Int,
  val totalDurationMs: Long,
)

data class FolderContents(
  val currentFolderUri: String?,
  val currentPathSegments: List<String>,
  val canNavigateUp: Boolean,
  val parentFolderUri: String?,
  val folders: List<FolderEntry>,
  val tracks: List<AudioTrack>,
) {
  companion object {
    val EMPTY =
      FolderContents(
        currentFolderUri = null,
        currentPathSegments = emptyList(),
        canNavigateUp = false,
        parentFolderUri = null,
        folders = emptyList(),
        tracks = emptyList(),
      )
  }
}
