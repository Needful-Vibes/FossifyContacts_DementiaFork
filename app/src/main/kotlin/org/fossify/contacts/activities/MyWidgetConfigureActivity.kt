package org.fossify.contacts.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.R
import org.fossify.contacts.adapters.SelectContactsAdapter
import org.fossify.contacts.databinding.WidgetConfigureMyBinding
import org.fossify.contacts.widgets.MyWidgetProvider

// Dementia fork: configuration screen for the direct-call widget. The user picks a contact,
// the widget is added immediately and tapping it later calls the contact without confirmation.
class MyWidgetConfigureActivity : SimpleActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val binding by viewBinding(WidgetConfigureMyBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setResult(RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // this activity is also the tap target of the widget, it performs the direct call
        if (intent?.action == Intent.ACTION_CALL) {
            callContactFromWidget(intent.getIntExtra(CONTACT_ID, 0), intent.getBooleanExtra(IS_PRIVATE, false))
            finish()
            return
        }

        setupEdgeToEdge(padBottomSystem = listOf(binding.widgetConfigureList))

        handlePermission(PERMISSION_READ_CONTACTS) { granted ->
            if (granted) {
                loadContacts()
            } else {
                toast(org.fossify.commons.R.string.no_contacts_permission)
                finish()
            }
        }
    }

    private fun loadContacts() {
        ContactsHelper(this).getContacts(showOnlyContactsWithNumbers = true) { contacts ->
            runOnUiThread {
                if (isDestroyed || isFinishing) {
                    return@runOnUiThread
                }

                if (contacts.isEmpty()) {
                    toast(R.string.no_contacts_with_phone_numbers)
                    finish()
                    return@runOnUiThread
                }

                setupContactsList(contacts)
            }
        }
    }

    private fun setupContactsList(contacts: ArrayList<Contact>) {
        val adapter = SelectContactsAdapter(
            activity = this,
            contacts = contacts,
            selectedContacts = ArrayList(),
            allowPickMultiple = false,
            recyclerView = binding.widgetConfigureList,
        ) { contact ->
            contactSelected(contact)
        }
        binding.widgetConfigureList.adapter = adapter
    }

    private fun contactSelected(contact: Contact) {
        MyWidgetProvider.storeContactId(this, appWidgetId, contact.id, contact.isPrivate())

        val appWidgetManager = AppWidgetManager.getInstance(this)
        MyWidgetProvider.updateWidget(this, appWidgetManager, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }

    // called when the widget itself is tapped, resolves the number and calls it right away
    private fun callContactFromWidget(contactId: Int, isPrivate: Boolean) {
        if (contactId == 0) {
            return
        }

        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            toast(org.fossify.commons.R.string.no_contacts_permission)
            return
        }

        val contact = if (isPrivate) {
            LocalContactsHelper(this).getContactWithId(contactId)
        } else {
            ContactsHelper(this).getContactWithId(contactId, false)
        }

        if (contact == null) {
            toast(R.string.widget_contact_deleted)
            return
        }

        if (contact.phoneNumbers.isEmpty()) {
            toast(org.fossify.commons.R.string.no_phone_number_found)
            return
        }

        // no confirmation dialog, the tap on the widget is the confirmation
        tryInitiateCall(contact) { number ->
            handlePermission(PERMISSION_CALL_PHONE) { granted ->
                val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
                Intent(action).apply {
                    data = Uri.fromParts("tel", number, null)
                    launchActivityIntent(this)
                }
            }
        }
    }
}
