package com.nuvio.app.features.profiles

/**
 * DebridOne seam: lets a platform offer "choose a picture from this computer" for profile avatars.
 * [pickImage] shows a file chooser, stores a copy of the chosen image in the app's data directory
 * and returns its `file:` URL, or null when the user cancels.
 */
object LocalAvatarImporter {
    @Volatile
    var pickImage: (suspend () -> String?)? = null
}
