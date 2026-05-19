package com.qtone.app.update

/**
 * Shape of the update manifest JSON hosted at [UpdateChecker.MANIFEST_URL].
 *
 * Example minimal payload:
 *   {
 *     "versionCode":  101,
 *     "versionName":  "1.0.1",
 *     "apkUrl":       "https://example.com/qtone-1.0.1.apk"
 *   }
 *
 * Optional fields:
 *   - changelog: short text shown in the "Update available" dialog so users
 *     know what changed. Plain text, newlines respected. Omit / leave blank to
 *     show no body text.
 *   - mandatory: if true, the dialog has no "Later" button — the user must
 *     update or close the app. Use for critical security/data fixes. Default
 *     false.
 *   - minSupportedVersionCode: if the installed versionCode is below this,
 *     also treated as mandatory. Lets a server-side policy force-update old
 *     installs without touching the per-release "mandatory" flag.
 */
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val changelog: String? = null,
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int = 0
) {
    fun isValid(): Boolean = versionCode > 0 && apkUrl.isNotBlank() && versionName.isNotBlank()
}
