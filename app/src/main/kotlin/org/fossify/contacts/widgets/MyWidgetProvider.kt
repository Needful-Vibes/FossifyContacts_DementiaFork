package org.fossify.contacts.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.R
import org.fossify.contacts.activities.MyWidgetConfigureActivity

class MyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // resolving contacts touches the contacts DB, AppWidgetProvider callbacks run on the main thread
        ensureBackgroundThread {
            appWidgetIds.forEach { appWidgetId ->
                updateWidget(context, appWidgetManager, appWidgetId)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // the widget was resized, the name fitting depends on the available width
        ensureBackgroundThread {
            updateWidget(context, appWidgetManager, appWidgetId, newOptions)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            // forget the contact linked to the removed widget instance
            prefs(context).edit().remove(getContactIdKey(appWidgetId)).remove(getContactPrivateKey(appWidgetId)).apply()
        }
    }

    companion object {
        // widget sizes below this threshold are treated as "icon only", without any text
        private const val ICON_ONLY_MAX_WIDTH_DP = 70
        // width in dp below which we prefer showing just the first name instead of the full name
        private const val FIRST_NAME_ONLY_MAX_WIDTH_DP = 120
        // text sizes in sp for the widget label, depending on the available width
        private const val LABEL_TEXT_SIZE_NARROW_SP = 16f
        private const val LABEL_TEXT_SIZE_SP = 18f
        private const val LABEL_TEXT_SIZE_PLACEHOLDER_SP = 14f

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle? = null
        ) {
            val prefs = prefs(context)
            val contactId = prefs.getInt(getContactIdKey(appWidgetId), 0)
            val isPrivate = prefs.getBoolean(getContactPrivateKey(appWidgetId), false)

            val views = RemoteViews(context.packageName, R.layout.widget_my)
            views.setOnClickPendingIntent(R.id.widget_holder, getClickPendingIntent(context, appWidgetId))

            if (contactId == 0) {
                // no contact picked yet, tapping opens the configuration screen again
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_call_contact))
                views.setTextViewTextSize(R.id.widget_label, TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_PLACEHOLDER_SP)
                views.setImageViewResource(R.id.widget_image, R.drawable.ic_phone_widget)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val contact = getContactSync(context, contactId, isPrivate)
            if (contact == null) {
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_contact_deleted))
                views.setTextViewTextSize(R.id.widget_label, TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_PLACEHOLDER_SP)
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            val widgetWidthDp = getWidgetWidthDp(appWidgetManager, appWidgetId, newOptions)
            setupWidgetViews(context, views, contact, widgetWidthDp)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        // Dementia fork: place a simple phone icon with the contact name next to/below it.
        // Tapping the widget calls the contact directly, without any confirmation.
        private fun setupWidgetViews(context: Context, views: RemoteViews, contact: Contact, widgetWidthDp: Int) {
            val fullName = contact.getNameToDisplay()
            val showLabel = widgetWidthDp > ICON_ONLY_MAX_WIDTH_DP
            views.setViewVisibility(R.id.widget_label, if (showLabel) View.VISIBLE else View.GONE)

            // use the full name if the widget is wide enough, first name only otherwise
            val nameToShow = if (widgetWidthDp > FIRST_NAME_ONLY_MAX_WIDTH_DP || contact.firstName.isEmpty()) {
                fullName
            } else {
                contact.firstName
            }
            views.setTextViewText(R.id.widget_label, nameToShow)

            // keep the text large and readable, smaller on narrow widgets
            val textSizeSp = when {
                widgetWidthDp <= ICON_ONLY_MAX_WIDTH_DP -> 0f
                widgetWidthDp < FIRST_NAME_ONLY_MAX_WIDTH_DP -> LABEL_TEXT_SIZE_NARROW_SP
                else -> LABEL_TEXT_SIZE_SP
            }
            views.setTextViewTextSize(R.id.widget_label, TypedValue.COMPLEX_UNIT_SP, textSizeSp)

            val iconSizePx = if (widgetWidthDp > ICON_ONLY_MAX_WIDTH_DP) {
                context.resources.getDimensionPixelSize(R.dimen.widget_icon_size)
            } else {
                context.resources.getDimensionPixelSize(R.dimen.widget_icon_size_icon_only)
            }
            val icon = context.resources.getDrawable(R.drawable.ic_phone_widget).mutate()
            icon.setBounds(0, 0, iconSizePx, iconSizePx)
            views.setImageViewBitmap(R.id.widget_image, icon.convertToBitmap())
        }

        // resolving the contact requires the contacts permission, without it we cannot render the widget
        private fun getContactSync(context: Context, contactId: Int, isPrivate: Boolean): Contact? {
            if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
                return null
            }

            return ContactsHelper(context).getContactWithId(contactId, isPrivate)
        }

        private fun getWidgetWidthDp(
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            newOptions: Bundle?
        ): Int {
            val options = newOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            return if (minWidth > 0) {
                minWidth
            } else {
                // fall back to a sane default when the host did not provide any size
                FIRST_NAME_ONLY_MAX_WIDTH_DP
            }
        }

        // tapping the widget calls the contact directly, no confirmation screen
        private fun getClickPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val prefs = prefs(context)
            val contactId = prefs.getInt(getContactIdKey(appWidgetId), 0)
            val isPrivate = prefs.getBoolean(getContactPrivateKey(appWidgetId), false)

            val intent = Intent(context, MyWidgetConfigureActivity::class.java).apply {
                action = Intent.ACTION_CALL
                data = Uri.fromParts("tel", "$contactId", null)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(CONTACT_ID, contactId)
                putExtra(IS_PRIVATE, isPrivate)
                // each widget instance needs its own pending intent
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun storeContactId(context: Context, appWidgetId: Int, contactId: Int, isPrivate: Boolean) {
            prefs(context).edit()
                .putInt(getContactIdKey(appWidgetId), contactId)
                .putBoolean(getContactPrivateKey(appWidgetId), isPrivate)
                .apply()
        }

        fun removeContactId(context: Context, appWidgetId: Int) {
            prefs(context).edit()
                .remove(getContactIdKey(appWidgetId))
                .remove(getContactPrivateKey(appWidgetId))
                .apply()
        }

        private fun getContactIdKey(appWidgetId: Int) = "widget_contact_id_$appWidgetId"

        private fun getContactPrivateKey(appWidgetId: Int) = "widget_contact_private_$appWidgetId"

        private fun prefs(context: Context): SharedPreferences = context.getSharedPrefs()
    }
}
