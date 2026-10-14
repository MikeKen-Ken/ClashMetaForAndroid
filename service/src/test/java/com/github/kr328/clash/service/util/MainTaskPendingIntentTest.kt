package com.github.kr328.clash.service.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTaskPendingIntentTest {
    @Test fun resumeFlagsKeepTheExistingTask() {
        val flags = flagsToResumeExistingTask(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )

        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags)
    }
}
