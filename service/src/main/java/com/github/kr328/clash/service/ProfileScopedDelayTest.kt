package com.github.kr328.clash.service

/** Completion may only clear the selection belonging to the tested profile. */
internal suspend fun <Profile, Result> runProfileScopedDelayTest(
    activeProfile: () -> Profile?,
    test: suspend () -> Result,
    hasSuccess: (Result) -> Boolean,
    clearSelection: (Profile) -> Unit,
): Result {
    val testedProfile = activeProfile()
    val result = test()
    if (hasSuccess(result) && testedProfile != null && activeProfile() == testedProfile) {
        // Do not read activeProfile again in the delete: activation can race
        // with this check, but the captured key can never delete another profile.
        clearSelection(testedProfile)
    }
    return result
}
