package org.openlife.app.intake

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.openlife.app.MainActivity
import org.openlife.app.OpenLifeApp
import org.openlife.app.ui.applySecureWindow
import java.util.UUID

/**
 * P1-20-R5 (AS-5 / A-14): the one entry for navigation from inside OpenLife
 * ("Open existing" now, reminder taps in P4-04). It is not exported, so no
 * other app can start it, and it still checks a typed kind, a well-formed id
 * and the single-use per-process nonce before handing the link to
 * MainActivity through memory ([InternalLinks]). It shows nothing and
 * finishes at once; MainActivity reads no extra from the intent it sends.
 */
class InternalLinkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureWindow()
        val links = (application as OpenLifeApp).container.internalLinks
        val accepted = savedInstanceState == null && links.accept(
            kind = intent.getStringExtra(EXTRA_KIND),
            rawId = intent.getStringExtra(EXTRA_SOURCE_ID),
            nonce = intent.getStringExtra(EXTRA_NONCE),
        )
        if (accepted) startActivity(Intent(this, MainActivity::class.java).addFlags(openExistingIntentFlags()))
        finish()
    }

    companion object {
        private const val EXTRA_KIND = "org.openlife.app.intake.InternalLinkActivity.kind"
        private const val EXTRA_SOURCE_ID = "org.openlife.app.intake.InternalLinkActivity.sourceId"
        private const val EXTRA_NONCE = "org.openlife.app.intake.InternalLinkActivity.nonce"

        /** An "open this source" link with a fresh nonce; only code in this process can build a valid one. */
        fun openSourceIntent(context: Context, sourceId: UUID): Intent {
            val links = (context.applicationContext as OpenLifeApp).container.internalLinks
            return Intent(context, InternalLinkActivity::class.java)
                .putExtra(EXTRA_KIND, InternalLinks.KIND_OPEN_SOURCE)
                .putExtra(EXTRA_SOURCE_ID, sourceId.toString())
                .putExtra(EXTRA_NONCE, links.issueNonce())
        }
    }
}
