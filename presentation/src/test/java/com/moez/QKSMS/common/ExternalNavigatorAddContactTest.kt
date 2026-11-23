package dev.octoshrimpy.quik.common

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.ContactsContract
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own one loads Realm's native library, which isn't available on the JVM.
@Config(application = Application::class)
class ExternalNavigatorAddContactTest {

    private lateinit var app: Application
    private lateinit var navigator: ExternalNavigator

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        navigator = ExternalNavigator(
            app,
            mock(PermissionManager::class.java),
            mock(NotificationManager::class.java)
        )
    }

    @Test
    fun addContact_offersExistingContacts_whenContactsAppSupportsIt() {
        val contacts = ComponentName("com.example.contacts", "com.example.contacts.Picker")
        shadowOf(app.packageManager).addActivityIfNotPresent(contacts)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            contacts,
            IntentFilter(Intent.ACTION_INSERT_OR_EDIT).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            }
        )

        navigator.addContact("+15550100")

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT_OR_EDIT, started.action)
        assertEquals(ContactsContract.Contacts.CONTENT_ITEM_TYPE, started.type)
        assertEquals("+15550100", started.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }

    @Test
    fun addContact_fallsBackToNewContactForm_whenInsertOrEditIsUnhandled() {
        navigator.addContact("+15550100")

        // Nothing handles either intent here, so startActivityExternal wraps the insert in a chooser.
        val started = shadowOf(app).nextStartedActivity
        val target = if (started.action == Intent.ACTION_CHOOSER)
            started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!! else started
        assertEquals(Intent.ACTION_INSERT, target.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, target.type)
        assertEquals("+15550100", target.getStringExtra(ContactsContract.Intents.Insert.PHONE))
    }
}
