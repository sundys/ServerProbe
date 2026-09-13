package com.serverprobe.manager.remote

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import android.os.Bundle
import com.serverprobe.manager.R
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class EditLinkActivity : FragmentActivity() {

    private lateinit var store: LinkStore
    private var editing: Link? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        applySystemBarInsets(findViewById(R.id.rootEdit))
        store = LinkStore(this)

        val etUrl = findViewById<EditText>(R.id.etUrl)
        val preview = findViewById<View>(R.id.previewCard)
        val tvName = findViewById<TextView>(R.id.tvName)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val tvHost = findViewById<TextView>(R.id.tvHost)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnSave = findViewById<TextView>(R.id.btnSave)

        if (intent.action == Intent.ACTION_SEND) {
            LinkParser.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                ?.let { etUrl.setText(it) }
        }
        if (intent.hasExtra(EXTRA_ID)) {
            editing = store.find(intent.getStringExtra(EXTRA_ID).orEmpty())
            editing?.let {
                etUrl.setText(it.url)
                findViewById<TextView>(R.id.tvTitle).setText(R.string.title_edit)
                btnSave.text = getString(R.string.update)
            }
        }

        fun revalidate() {
            val raw = etUrl.text.toString()
            val parsed = LinkParser.parse(raw, editing)
            if (raw.isBlank()) {
                preview.visibility = View.GONE
                btnSave.isEnabled = false
                btnSave.alpha = 0.45f
                return
            }
            preview.visibility = View.VISIBLE
            if (parsed == null) {
                tvName.text = "—"
                tvVersion.text = "—"
                tvHost.text = "—"
                tvStatus.text = getString(R.string.parse_failed)
                tvStatus.setTextColor(0xFFD32F2F.toInt())
                btnSave.isEnabled = false
                btnSave.alpha = 0.45f
            } else {
                tvName.text = parsed.name
                tvVersion.text = if (parsed.version.isEmpty())
                    getString(R.string.version_unknown)
                else
                    "v${parsed.version}"
                tvHost.text = parsed.host
                tvStatus.text = getString(R.string.parse_ok)
                tvStatus.setTextColor(0xFF2E7D32.toInt())
                btnSave.isEnabled = true
                btnSave.alpha = 1f
                btnSave.tag = parsed
            }
        }

        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = revalidate()
        })

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnSave.setOnClickListener {
            val parsed = btnSave.tag as? Link ?: return@setOnClickListener
            store.upsert(parsed)
            Toast.makeText(this, getString(R.string.saved_toast, parsed.name), Toast.LENGTH_SHORT).show()
            finish()
        }

        revalidate()
    }

    /** Android 15 强制边到边：内容避让系统状态栏与键盘 */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    companion object {
        const val EXTRA_ID = "edit_id"

        fun edit(activity: Activity, link: Link) {
            activity.startActivity(
                Intent(activity, EditLinkActivity::class.java).putExtra(EXTRA_ID, link.id)
            )
        }
    }
}
