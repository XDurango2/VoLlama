package com.XDurango.VoLlama.testutil

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk

/** Task<Void> ya resuelto (éxito, o fallo si [error] != null), con listeners síncronos. */
fun completedTask(error: Exception? = null): Task<Void> {
    val task = mockk<Task<Void>>()
    every { task.addOnSuccessListener(any<OnSuccessListener<Void>>()) } answers {
        if (error == null) firstArg<OnSuccessListener<Void?>>().onSuccess(null)
        task
    }
    every { task.addOnFailureListener(any<OnFailureListener>()) } answers {
        if (error != null) firstArg<OnFailureListener>().onFailure(error)
        task
    }
    return task
}

fun failedTask(statusCode: Int): Task<Void> = completedTask(ApiException(Status(statusCode)))
