package com.ashavaani.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthClientTest {

    @Test
    fun `validates email and password constraints cleanly`() {
        assertTrue(SupabaseAuthClient.isValidEmail("asha.sunita@gmail.com"))
        assertTrue(SupabaseAuthClient.isValidEmail("worker@nhm.gov.in"))
        assertFalse(SupabaseAuthClient.isValidEmail("not-an-email"))

        assertNull(SupabaseAuthClient.validateEmailAndPassword("asha@gmail.com", "secret123"))
        assertNotNull(SupabaseAuthClient.validateEmailAndPassword("bad", "secret123"))
        assertNotNull(SupabaseAuthClient.validateEmailAndPassword("asha@gmail.com", "12345"))
    }

    @Test
    fun `builds Google OAuth authorize URL and parses deep-link callback`() = runBlocking {
        val client = SupabaseAuthClient(
            supabaseUrl = "https://ulznnflntffpuxxmjdip.supabase.co",
            supabaseKey = "sb_publishable_test",
        )
        val oauthUrl = client.buildGoogleOAuthUrl("ashavaani://auth/callback")
        assertTrue(oauthUrl.contains("https://ulznnflntffpuxxmjdip.supabase.co/auth/v1/authorize"))
        assertTrue(oauthUrl.contains("provider=google"))
        assertTrue(oauthUrl.contains("redirect_to=ashavaani%3A%2F%2Fauth%2Fcallback"))

        val parsed = client.parseOAuthCallbackUri(
            "ashavaani://auth/callback#access_token=jwt_tok_123&email=lakshmi.phc%40gmail.com&token_type=bearer"
        )
        assertTrue(parsed.isSuccess)
        val session = parsed.getOrThrow()
        assertEquals("google", session.provider)
        assertEquals("lakshmi.phc@gmail.com", session.email)
        assertEquals("jwt_tok_123", session.accessToken)
    }

    @Test
    fun `gmail sign-in rejects non-gmail domains and succeeds for valid gmail`() = runBlocking {
        val client = SupabaseAuthClient(supabaseUrl = "http://127.0.0.1:1", supabaseKey = "test")
        val bad = client.signInWithGmail("worker@yahoo.com")
        assertTrue(bad.isFailure)

        val good = client.signInWithGmail("sunita.asha@gmail.com", "Sunita Devi")
        assertTrue(good.isSuccess)
        assertEquals("google", good.getOrThrow().provider)
        assertEquals("sunita.asha@gmail.com", good.getOrThrow().email)
        assertEquals("Sunita Devi", good.getOrThrow().displayName)
    }
}
