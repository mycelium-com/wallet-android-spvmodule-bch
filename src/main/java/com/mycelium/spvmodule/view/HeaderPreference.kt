package com.mycelium.spvmodule.view

import android.content.Context
import android.support.v7.preference.Preference
import android.support.v7.preference.PreferenceViewHolder
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.mycelium.spvmodule.R
import com.mycelium.spvmodule.SpvModuleApplication


private val DESCRIPTION_FIRST_SHOW = "description_first_show"

class HeaderPreference(context: Context?, attrs: AttributeSet?) : Preference(context, attrs) {
    var openListener: (() -> Unit)? = null
    var buttonListener: (() -> Unit)? = null

    private var syncState: TextView? = null
    private var syncStateText: String? = null

    private var descriptionView: TextView? = null
    private var descriptionView2: TextView? = null
    private var expand = false

    private var buttonText: String? = null
    private var button: TextView? = null

    init {
        widgetLayoutResource = R.layout.preference_button_uninstall
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)
        val isMBWInstalled = SpvModuleApplication.isMbwInstalled(context)

        holder?.itemView?.apply {
            val buttonOpenText = if (isMBWInstalled) R.string.open_mycelium_wallet else R.string.install_mycelium_wallet
            val textVisibility = if (isMBWInstalled) View.GONE else View.VISIBLE
            val secondButton = holder.itemView?.findViewById<View>(R.id.second_button) as TextView
            secondButton.setText(buttonOpenText)
            (holder.itemView?.findViewById<View>(R.id.installWarning))?.visibility = textVisibility
            secondButton.setOnClickListener {
                openListener?.invoke()
            }
            isClickable = false
            descriptionView2 = findViewById(R.id.description_2)
            descriptionView = findViewById(R.id.description)
            val description = Html.fromHtml(context.getString(R.string.inner_module_description))
            findViewById<TextView>(R.id.description)?.text = description

            syncState = findViewById(R.id.sync_state)
            syncState?.text = syncStateText

            val moreDescription = findViewById<ImageView>(R.id.more_description)
            expand = sharedPreferences.getBoolean(DESCRIPTION_FIRST_SHOW, true)
            updateDescription(moreDescription, expand);
            sharedPreferences.edit().putBoolean(DESCRIPTION_FIRST_SHOW, false).apply()

            moreDescription.setOnClickListener {
                expand = !expand
                updateDescription(it, expand)
            }
            button = findViewById(R.id.first_button)
            button?.text = buttonText
            button?.setOnClickListener {
                buttonListener?.invoke()
            }
        }
    }

    private fun updateDescription(it: View, expand: Boolean) {
        it.rotation = if (expand) 180f else 0f
        if (expand) {
            descriptionView2?.visibility = View.VISIBLE
            descriptionView?.maxLines = 10
        } else {
            descriptionView2?.visibility = View.GONE
            descriptionView?.maxLines = 2
        }
    }

    fun setSyncStateText(syncStateText: String) {
        this.syncStateText = syncStateText
        syncState?.text = syncStateText
    }

    fun setButtonText(text: String) {
        buttonText = text
        button?.text = text
    }

}