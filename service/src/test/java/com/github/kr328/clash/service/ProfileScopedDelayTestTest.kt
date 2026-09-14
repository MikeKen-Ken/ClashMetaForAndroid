package com.github.kr328.clash.service

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.*
import org.junit.Test

class ProfileScopedDelayTestTest {
    private fun runCompletion(initial: String?, after: String?, succeeded: Boolean): List<String> {
        var active = initial
        val deleted = mutableListOf<String>()
        lateinit var pending: Continuation<Boolean>
        var completed = false
        val task: suspend () -> Unit = {
            runProfileScopedDelayTest(
                activeProfile = { active },
                test = { suspendCoroutine<Boolean> { pending = it } },
                hasSuccess = { it },
                clearSelection = { deleted.add(it) },
            )
            completed = true
        }
        task.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { result.getOrThrow() }
        })
        assertFalse(completed)
        active = after
        pending.resumeWith(Result.success(succeeded))
        assertTrue(completed)
        return deleted
    }

    @Test fun clearsOnlyTheTestedProfileAfterSuccess() {
        assertEquals(listOf("A"), runCompletion("A", "A", true))
    }

    @Test fun switchingProfilesWhileSuspendedDoesNotDeleteNewSelection() {
        assertTrue(runCompletion("A", "B", true).isEmpty())
    }

    @Test fun failureOrMissingProfileNeverDeletesSelection() {
        assertTrue(runCompletion("A", "A", false).isEmpty())
        assertTrue(runCompletion(null, "B", true).isEmpty())
        assertTrue(runCompletion("A", null, true).isEmpty())
    }
}
