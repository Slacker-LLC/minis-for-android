package com.openminis.app.data

/**
 * Where this project lives. Every in-app link to the repository (About, bug report, update
 * check, privacy policy, OpenRouter attribution) is built from here, so moving or renaming the
 * repository is a one-line change instead of a hunt through the sources.
 */
object AppLinks {
    const val OWNER = "Slacker-LLC"
    const val REPO = "minis-for-android"

    const val REPOSITORY_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    const val NEW_ISSUE_URL = "$REPOSITORY_URL/issues/new"
    const val PRIVACY_POLICY_URL = "$REPOSITORY_URL/blob/main/PRIVACY.md"
    const val PRIVACY_POLICY_URL_ZH = "$REPOSITORY_URL/blob/main/PRIVACY.zh-CN.md"
    const val THIRD_PARTY_LICENSES_URL = "$REPOSITORY_URL/blob/main/THIRD_PARTY_LICENSES.md"
    const val LICENSE_URL = "$REPOSITORY_URL/blob/main/LICENSE"

    /** The Chinese policy for Chinese UI languages, the English one otherwise. */
    fun privacyPolicyUrl(language: String): String =
        if (language.equals("zh", ignoreCase = true)) PRIVACY_POLICY_URL_ZH else PRIVACY_POLICY_URL

    /** GitHub REST listing the update checker reads. */
    const val RELEASES_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
}
