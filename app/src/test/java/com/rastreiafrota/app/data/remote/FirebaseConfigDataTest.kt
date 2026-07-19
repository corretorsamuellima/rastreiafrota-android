package com.rastreiafrota.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseConfigDataTest {
    @Test
    fun acceptsValidAndroidFirebaseIdentifiers() {
        assertTrue(
            FirebaseConfigData(
                appId = "1:123456789012:android:abcdef0123456789",
                apiKey = "AIzaSyExampleKeyWithEnoughCharacters123",
                projectId = "rastreia-frota-123",
                senderId = "123456789012"
            ).valid()
        )
    }

    @Test
    fun rejectsMalformedConfiguration() {
        assertFalse(
            FirebaseConfigData(
                appId = "invalid",
                apiKey = "short",
                projectId = "../secret",
                senderId = "abc"
            ).valid()
        )
    }
}
