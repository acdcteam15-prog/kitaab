package com.pdfreader.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pdfreader.app.R
import com.pdfreader.app.data.sync.DriveStore
import com.pdfreader.app.databinding.ActivityLoginBinding
import com.pdfreader.app.ui.home.HomeActivity
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 1: sign in with Google (required). Step 2: type a name; the app creates
 * My Drive / books / <name> if it is missing. Then the Home screen opens.
 */
class LoginActivity : com.pdfreader.app.ui.base.BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var signInClient: GoogleSignInClient
    private lateinit var prefs: PreferenceManager
    private val RC_SIGN_IN = 1001

    // Full DRIVE scope: the app must see the books you put in your folder yourself, not only its own files
    private val DRIVE_SCOPE = Scope("https://www.googleapis.com/auth/drive")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager(this)

        // Someone who already has a profile has used the app before: no intro for them
        if (prefs.currentUser.isNotEmpty()) IntroActivity.markSeen(this)
        // First time ever: show the short intro first (it comes back here when it is done)
        if (!IntroActivity.isSeen(this)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSignIn()

        val account = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account != null && GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)

        if (hasDriveScope && prefs.currentUser.isNotEmpty()) {
            goHome()
            return
        }
        // Signed in but without the Drive permission: sign out and ask again
        if (account != null && !hasDriveScope) signInClient.signOut()

        binding.btnSignIn.setOnClickListener {
            binding.btnSignIn.isEnabled = false
            binding.progressSignIn.visibility = View.VISIBLE
            startActivityForResult(signInClient.signInIntent, RC_SIGN_IN)
        }
        binding.btnContinue.setOnClickListener { submitName() }
        binding.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitName(); true } else false
        }

        if (hasDriveScope) showNameStep() else showSignInStep()
    }

    private fun setupSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_SCOPE)
            .build()
        signInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun showSignInStep() {
        binding.groupSignIn.visibility = View.VISIBLE
        binding.groupName.visibility = View.GONE
    }

    private fun showNameStep() {
        binding.groupSignIn.visibility = View.GONE
        binding.groupName.visibility = View.VISIBLE
        if (binding.etName.text.isNullOrEmpty()) binding.etName.setText(prefs.currentUser)
        val given = GoogleSignIn.getLastSignedInAccount(this)?.givenName
        if (!given.isNullOrBlank()) binding.etName.hint = given
    }

    /** What the user typed, trimmed. Null (with a message shown) when it cannot be a folder name. */
    private fun validName(): String? {
        val name = binding.etName.text?.toString().orEmpty().trim().replace(Regex("\\s+"), " ")
        val problem = when {
            name.isEmpty() -> getString(R.string.login_name_empty)
            name.length > 40 -> getString(R.string.login_name_long)
            name.startsWith(".") || name.any { it in "/\\:*?\"<>|" } -> getString(R.string.login_name_chars)
            else -> null
        }
        binding.tvNameHint.visibility = if (problem == null) View.GONE else View.VISIBLE
        binding.tvNameHint.text = problem.orEmpty()
        return if (problem == null) name else null
    }

    private fun submitName() {
        val typed = validName() ?: return
        binding.btnContinue.isEnabled = false
        binding.progressName.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = DriveStore(applicationContext)
                    val drive = store.driveService() ?: throw IllegalStateException("Not signed in to Google")
                    // Creates My Drive / books / <name> when missing; returns the real folder name
                    store.resolveUserFolder(drive, typed).second
                }
            }
            binding.btnContinue.isEnabled = true
            binding.progressName.visibility = View.GONE
            result.fold(
                onSuccess = { folderName ->
                    prefs.currentUser = folderName
                    goHome()
                },
                onFailure = { e ->
                    binding.tvNameHint.visibility = View.VISIBLE
                    binding.tvNameHint.text = getString(R.string.login_folder_failed, e.message ?: e.javaClass.simpleName)
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            binding.btnSignIn.isEnabled = true
            binding.progressSignIn.visibility = View.GONE
            try {
                GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
                showNameStep()
            } catch (e: ApiException) {
                Toast.makeText(this, "Sign-in failed (code ${e.statusCode})", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goHome() {
        // Restart Firebase listener now that we know the user
        (application as? com.pdfreader.app.KitaabApplication)?.restartFirebaseListener()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
