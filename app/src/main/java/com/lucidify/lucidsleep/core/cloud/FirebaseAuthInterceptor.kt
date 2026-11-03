package com.lucidify.lucidsleep.core.cloud

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Adds the Firebase ID token to every request.
 *
 * Token retrieval is bounded. Firebase refreshes the ID token roughly hourly, and a
 * refresh that never completes must not block the calling thread forever: a blocked
 * call here would hold the sync engine's single-pass lock and silently stop all
 * uploads for the rest of the process lifetime. Failures surface as IOException so
 * callers treat them as ordinary retryable network errors.
 */
class FirebaseAuthInterceptor(
    private val tokenTimeoutMs: Long = DEFAULT_TOKEN_TIMEOUT_MS
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // A signed Cloud Storage URL carries its own authorization in the query string.
        // Send the user's Firebase ID token only to Lucidify's API, never to storage.
        if (req.url.queryParameter("X-Goog-Signature") != null) return chain.proceed(req)
        val user = FirebaseAuth.getInstance().currentUser ?: return chain.proceed(req)

        // 1) try cached token (Firebase refreshes it if expired)
        val token = fetchToken(user, forceRefresh = false)
        val resp = chain.proceed(authedReq(req, token))
        if (resp.code != 401) return resp
        resp.close()

        // 2) force refresh then retry once
        val fresh = fetchToken(user, forceRefresh = true)
        return chain.proceed(authedReq(req, fresh))
    }

    private fun authedReq(req: Request, token: String?): Request =
        if (!token.isNullOrBlank()) {
            req.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else req

    private fun fetchToken(user: FirebaseUser, forceRefresh: Boolean): String? =
        try {
            Tasks.await(user.getIdToken(forceRefresh), tokenTimeoutMs, TimeUnit.MILLISECONDS).token
        } catch (e: TimeoutException) {
            throw IOException("Firebase ID token fetch timed out after $tokenTimeoutMs ms (forceRefresh=$forceRefresh)", e)
        } catch (e: ExecutionException) {
            throw IOException("Firebase ID token fetch failed: ${e.cause?.message ?: e.message}", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Firebase ID token fetch interrupted", e)
        }

    companion object {
        const val DEFAULT_TOKEN_TIMEOUT_MS = 15_000L
    }
}