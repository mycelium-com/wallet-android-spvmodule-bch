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
import android.widget.Button
import android.widget.TextView
import com.mycelium.spvmodule.R
import com.mycelium.spvmodule.SpvModuleApplication


class HeaderPreference(context: Context?, attrs: AttributeSet?) : Preference(context, attrs) {
    var openListener: (() -> Unit)? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)
        val isMBWInstalled = SpvModuleApplication.isMbwInstalled(context)

        holder?.itemView?.apply {
            val buttonText = if (isMBWInstalled) R.string.open_mycelium_wallet else R.string.install_mycelium_wallet
            val textVisibility = if (isMBWInstalled) View.GONE else View.VISIBLE

            (holder.itemView?.findViewById<View>(R.id.open_text) as TextView).setText(buttonText)
            (holder.itemView?.findViewById<View>(R.id.installWarning))?.visibility = textVisibility

            findViewById<View>(R.id.open)?.setOnClickListener {
                openListener?.invoke()
            }
            isClickable = false
            val descriptionView = findViewById<TextView>(R.id.description)
            val description = Html.fromHtml(context.getString(R.string.inner_module_description))
            val ssb = SpannableStringBuilder(description)
            val tag = "_logo_"
            val startIndex = description.indexOf(tag)
            val lineHeight = 3 * (descriptionView?.lineHeight ?: 0) / 2
            val logo = context.resources.getDrawable(R.drawable.ic_mycelium_wallet)
            logo.setBounds(0, 0, lineHeight, lineHeight)
            ssb.setSpan(ImageSpan(logo), startIndex, startIndex + tag.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
            findViewById<TextView>(R.id.description)?.setText(ssb, TextView.BufferType.SPANNABLE)
        }
    }
}