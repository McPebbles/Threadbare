package com.threadbare.client.shell

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import com.threadbare.client.R
import com.threadbare.client.util.Prefs
import com.threadbare.client.util.SavedKind
import com.threadbare.client.util.SavedStore
import com.threadbare.client.util.Safely
import com.threadbare.client.web.RedditPath

/**
 * The app's chrome: back, a subreddit box, and the overflow.
 *
 * The bar stays deliberately small — Tombot's revision established that a
 * hand-rolled bar duplicating the site's own navigation reads as a rendering
 * bug. What is here is what the *app* owns and the site cannot provide to a
 * logged-out reader: getting to a subreddit by name without going through
 * search, and bookmarks, which on Reddit require an account.
 */
class TopBar(
    private val activity: Activity,
    root: View,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onBack()
        fun onReload()
        fun onHome()
        fun onOpenExternally()
        fun onShare()
        fun onCopyLink()
        fun onSettings()
        fun onSignIn()
        fun onClearSession()
        fun onGoToSubreddit(name: String)
        fun onBadSubreddit(typed: String)
        fun onToggleSaved(kind: SavedKind)
        fun onShowSaved(kind: SavedKind)
        fun currentUrl(): String?
        fun currentTitle(): String?
    }

    val wrapper: View = root.findViewById(R.id.topBarWrapper)
    val row: View = root.findViewById(R.id.topBarRow)
    private val backButton: ImageButton = root.findViewById(R.id.buttonBack)
    private val overflowButton: ImageButton = root.findViewById(R.id.buttonOverflow)
    private val input: EditText = root.findViewById(R.id.subredditInput)

    /** Guards the normalising TextWatcher against re-entering itself. */
    private var editing = false

    init {
        // Back is persistent and routes through the same path as the system
        // gesture, so the two can never disagree about what "back" means.
        backButton.setOnClickListener { callbacks.onBack() }
        overflowButton.setOnClickListener { showOverflow(it) }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        // Tapping the box offers to replace what is there rather than making
        // the user clear a subreddit name one character at a time.
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) input.post { Safely.run { input.selectAll() } }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

            /**
             * Normalise as the user types or pastes.
             *
             * This is the second half of "not a URL bar", and it runs on paste
             * as well as on typing — which matters, because pasting a whole
             * reddit.com link into this box is the obvious thing to try. An
             * InputFilter alone would mangle that paste into nonsense by
             * dropping its punctuation; normalising the finished text instead
             * turns it into the subreddit it names, and turns anything that is
             * not a subreddit into nothing at all.
             */
            override fun afterTextChanged(s: Editable?) {
                if (editing || s == null) return
                val typed = s.toString()
                val cleaned = sanitise(typed)
                if (cleaned != typed) {
                    editing = true
                    Safely.run {
                        s.replace(0, s.length, cleaned)
                        input.setSelection(cleaned.length)
                    }
                    editing = false
                }
            }
        })
    }

    /**
     * Strip anything that could not be part of a subreddit name.
     *
     * A pasted URL is handled up front by [RedditPath.normaliseName], which
     * reads the subreddit out of it. Everything else is reduced to the
     * allowlist: letters, digits, underscore, and `+` for multireddits. A
     * leading `r/` is dropped because people type it out of habit.
     */
    private fun sanitise(raw: String): String {
        if (raw.contains("://")) {
            return RedditPath.normaliseName(raw).orEmpty()
        }
        var s = raw.trimStart('/')
        if (s.length >= 2 && s.substring(0, 2).equals("r/", ignoreCase = true)) {
            s = s.substring(2)
        }
        return s.filter { (it.isLetterOrDigit() && it.code < 128) || it == '_' || it == '+' }
    }

    private fun submit() {
        val name = RedditPath.normaliseName(input.text?.toString())
        hideKeyboard()
        input.clearFocus()
        if (name == null) {
            callbacks.onBadSubreddit(input.text?.toString().orEmpty())
            // Put the box back to where the user actually is, so a rejected
            // entry does not leave the bar lying about the current page.
            setLabel(callbacks.currentUrl())
            return
        }
        callbacks.onGoToSubreddit(name)
    }

    private fun hideKeyboard() {
        Safely.run {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    /**
     * Reflect where the user is. Skipped while the box has focus, so a page
     * finishing in the background cannot overwrite something half-typed.
     */
    fun setLabel(url: String?) {
        if (input.hasFocus()) return
        val name = RedditPath.subredditOf(url).orEmpty()
        if (input.text?.toString() == name) return
        editing = true
        Safely.run { input.setText(name) }
        editing = false
    }

    // ------------------------------------------------------------- overflow

    private fun showOverflow(anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menuInflater.inflate(R.menu.overflow, menu.menu)

        val context: Context = activity
        val url = callbacks.currentUrl()
        val kind = RedditPath.kind(url)

        // Save items appear only where they mean something, and say which way
        // they will go rather than toggling something the user cannot see.
        val subUrl = RedditPath.subredditOf(url)?.let { RedditPath.subredditUrl(it) }
        val saveSub = menu.menu.findItem(R.id.action_save_subreddit)
        if (subUrl != null) {
            val saved = SavedStore.contains(context, SavedKind.SUBREDDIT, subUrl)
            saveSub.isVisible = true
            saveSub.setTitle(
                if (saved) R.string.action_unsave_subreddit else R.string.action_save_subreddit
            )
        } else {
            saveSub.isVisible = false
        }

        val postUrl = RedditPath.postPermalink(url)
        val savePost = menu.menu.findItem(R.id.action_save_post)
        if (kind == RedditPath.Kind.POST && postUrl != null) {
            val saved = SavedStore.contains(context, SavedKind.POST, postUrl)
            savePost.isVisible = true
            savePost.setTitle(
                if (saved) R.string.action_unsave_post else R.string.action_save_post
            )
        } else {
            savePost.isVisible = false
        }

        menu.menu.findItem(R.id.action_saved_subreddits)?.isVisible =
            SavedStore.of(context, SavedKind.SUBREDDIT).isNotEmpty()
        menu.menu.findItem(R.id.action_saved_posts)?.isVisible =
            SavedStore.of(context, SavedKind.POST).isNotEmpty()

        menu.menu.findItem(R.id.action_clear_session)?.isVisible =
            !Prefs.ephemeralSession(context)

        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> { callbacks.onReload(); true }
                R.id.action_home -> { callbacks.onHome(); true }
                R.id.action_save_subreddit -> { callbacks.onToggleSaved(SavedKind.SUBREDDIT); true }
                R.id.action_save_post -> { callbacks.onToggleSaved(SavedKind.POST); true }
                R.id.action_saved_subreddits -> { callbacks.onShowSaved(SavedKind.SUBREDDIT); true }
                R.id.action_saved_posts -> { callbacks.onShowSaved(SavedKind.POST); true }
                R.id.action_share -> { callbacks.onShare(); true }
                R.id.action_copy_link -> { callbacks.onCopyLink(); true }
                R.id.action_open_external -> { callbacks.onOpenExternally(); true }
                R.id.action_sign_in -> { callbacks.onSignIn(); true }
                R.id.action_clear_session -> { callbacks.onClearSession(); true }
                R.id.action_settings -> { callbacks.onSettings(); true }
                else -> false
            }
        }
        menu.show()
    }
}
