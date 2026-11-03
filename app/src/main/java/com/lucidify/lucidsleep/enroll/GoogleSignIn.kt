package com.lucidify.lucidsleep.enroll

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * "Sign in with Google" through Android's Credential Manager, then into Firebase with the
 * same Google account the sleeper or staff member uses on the Lucidify Cloud web site.
 */
object GoogleSignIn {

	sealed class Outcome {
		data object Success : Outcome()
		data object Cancelled : Outcome()
		/** [message] is for the screen; [code] is for the night log. */
		data class Failed(val message: String, val code: String) : Outcome()
	}

	/** [activityContext] must be an Activity: the account picker is shown over it. */
	suspend fun signIn(activityContext: Context): Outcome {
		val clientId = webClientId(activityContext)
			?: return Outcome.Failed(
				"This build is missing its Google sign-in settings. Contact Lucidify.",
				"no_default_web_client_id"
			)
		val request = GetCredentialRequest.Builder()
			.addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
			.build()

		val credential = try {
			CredentialManager.create(activityContext).getCredential(activityContext, request).credential
		} catch (e: GetCredentialCancellationException) {
			return Outcome.Cancelled
		} catch (e: NoCredentialException) {
			return Outcome.Failed("No Google account is set up on this phone. Add one in the phone's Settings, then try again.", "no_credential")
		} catch (e: GetCredentialException) {
			return Outcome.Failed("Google sign-in didn't work. Check that you're online and try again.", "${e.javaClass.simpleName}:${e.type}")
		}

		if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
			return Outcome.Failed("Google sign-in returned something unexpected. Try again.", "unexpected_credential:${credential.type}")
		}
		return try {
			val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
			FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
			Outcome.Success
		} catch (e: Exception) {
			Outcome.Failed("Google sign-in didn't work. Check that you're online and try again.", "firebase:${e.javaClass.simpleName}")
		}
	}

	/**
	 * Signs out of Firebase and tells Credential Manager to forget the chosen account, so
	 * the next "Sign in with Google" shows the account picker again.
	 */
	suspend fun signOut(context: Context) {
		FirebaseAuth.getInstance().signOut()
		runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
	}

	/**
	 * The web OAuth client ID that the google-services plugin writes from google-services.json.
	 * Looked up by name so a json without Google sign-in fails here with a clear message,
	 * not as a compile error.
	 */
	private fun webClientId(context: Context): String? {
		val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
		return if (id != 0) context.getString(id).takeIf { it.isNotBlank() } else null
	}
}