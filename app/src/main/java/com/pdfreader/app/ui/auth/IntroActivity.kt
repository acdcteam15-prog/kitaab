package com.pdfreader.app.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pdfreader.app.R
import com.pdfreader.app.databinding.ActivityIntroBinding

/** The few short steps shown the first time the app is opened. */
class IntroActivity : com.pdfreader.app.ui.base.BaseActivity() {

    private lateinit var b: ActivityIntroBinding
    private var index = 0

    private val icons = listOf("\uD83D\uDCD6", "\uD83D\uDD11", "\uD83D\uDCC1", "\uD83D\uDCF1")
    private val titles = intArrayOf(R.string.intro_1_title, R.string.intro_2_title, R.string.intro_3_title, R.string.intro_4_title)
    private val bodies = intArrayOf(R.string.intro_1_body, R.string.intro_2_body, R.string.intro_3_body, R.string.intro_4_body)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(b.root)
        index = savedInstanceState?.getInt("index", 0) ?: 0
        b.btnIntroSkip.setOnClickListener { finishIntro() }
        b.btnIntroNext.setOnClickListener {
            if (index >= titles.size - 1) finishIntro() else { index++; show() }
        }
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    private fun show() {
        b.tvIntroIcon.text = icons[index]
        b.tvIntroTitle.setText(titles[index])
        b.tvIntroBody.setText(bodies[index])
        b.tvIntroDots.text = titles.indices.joinToString("  ") { if (it == index) "\u25CF" else "\u25CB" }
        val last = index == titles.size - 1
        b.btnIntroNext.setText(if (last) R.string.intro_start else R.string.intro_next)
        b.btnIntroSkip.visibility = if (last) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    private fun finishIntro() {
        markSeen(this)
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS = "kitaab_intro"
        fun isSeen(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("seen", false)
        fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seen", true).apply()
        }
    }
}
