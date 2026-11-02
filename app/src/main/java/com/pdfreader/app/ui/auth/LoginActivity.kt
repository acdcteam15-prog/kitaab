package com.pdfreader.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pdfreader.app.databinding.ActivityLoginBinding
import com.pdfreader.app.ui.home.HomeActivity
import com.pdfreader.app.utils.PreferenceManager

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var signInClient: GoogleSignInClient
    private lateinit var prefs: PreferenceManager
    private val RC_SIGN_IN = 1001

    // Use the full DRIVE scope — allows reading ALL files in Drive, not just app-created ones
    private val DRIVE_SCOPE = Scope("https://www.googleapis.com/auth/drive")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager(this)

        setupSignIn()

        // Check if already signed in WITH the correct Drive scope
        val account = GoogleSignIn.getLastSignedInAccount(this)
        val hasDriveScope = account != null &&
            GoogleSignIn.hasPermissions(account, DRIVE_SCOPE)

        if (account != null && hasDriveScope && prefs.currentUser.isNotEmpty()) {
            goHome()
            return
        }

        // If signed in but missing scope, sign out and re-request
        if (account != null && !hasDriveScope) {
            signInClient.signOut()
        }

        setupProfileChips()
        setupButton()
    }

    private fun setupSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(DRIVE_SCOPE)
            .build()
        signInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun setupProfileChips() {
        when (prefs.currentUser) {
            "Bader"  -> selectUser("Bader")
            "Shirin" -> selectUser("Shirin")
        }
        binding.chipBader.setOnClickListener  { selectUser("Bader") }
        binding.chipShirin.setOnClickListener { selectUser("Shirin") }
    }

    private fun selectUser(name: String) {
        prefs.currentUser = name
        binding.chipBader.isChecked  = name == "Bader"
        binding.chipShirin.isChecked = name == "Shirin"
        binding.tvProfileHint.visibility = View.GONE
    }

    private fun setupButton() {
        binding.btnSignIn.setOnClickListener {
            if (prefs.currentUser.isEmpty()) {
                binding.tvProfileHint.visibility = View.VISIBLE
                binding.tvProfileHint.text = "Please select a profile above"
                return@setOnClickListener
            }
            binding.btnSignIn.isEnabled = false
            binding.progressSignIn.visibility = View.VISIBLE
            startActivityForResult(signInClient.signInIntent, RC_SIGN_IN)
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
                goHome()
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
