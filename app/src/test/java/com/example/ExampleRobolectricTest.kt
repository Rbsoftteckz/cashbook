package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.LedgerViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `verify app lock and security recovery logic`() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = LedgerViewModel(application)

    // Initially, app lock is disabled and app is unlocked
    assertFalse(viewModel.isAppLockEnabled.value)
    assertTrue(viewModel.isAppUnlocked.value)

    // Enable app lock
    val pin = "4321"
    val question = "What is your favorite city?"
    val answer = "Quetta"
    viewModel.enableAppLock(pin, question, answer)

    // Verify state after enabling
    assertTrue(viewModel.isAppLockEnabled.value)
    assertEquals(pin, viewModel.appPasscode.value)
    assertEquals(question, viewModel.securityQuestion.value)
    assertEquals(answer, viewModel.securityAnswer.value)
    assertTrue(viewModel.isAppUnlocked.value) // unlocked immediately upon set up

    // Mock app lock activation (on startup/backgrounding)
    viewModel.lockApp()
    assertFalse(viewModel.isAppUnlocked.value)

    // Try unlocking with incorrect PIN
    val unlockFailure = viewModel.unlockApp("1111")
    assertFalse(unlockFailure)
    assertFalse(viewModel.isAppUnlocked.value)

    // Unlock with correct PIN
    val unlockSuccess = viewModel.unlockApp("4321")
    assertTrue(unlockSuccess)
    assertTrue(viewModel.isAppUnlocked.value)

    // Mock forget lock activation again
    viewModel.lockApp()
    assertFalse(viewModel.isAppUnlocked.value)

    // Recovery with incorrect answer
    val resetFailure = viewModel.resetPasscodeViaSecurityAnswer("WrongAnswer", "5555")
    assertFalse(resetFailure)
    assertFalse(viewModel.isAppUnlocked.value)

    // Recovery with correct answer
    val resetSuccess = viewModel.resetPasscodeViaSecurityAnswer("Quetta", "5555")
    assertTrue(resetSuccess)
    assertTrue(viewModel.isAppUnlocked.value)
    assertEquals("5555", viewModel.appPasscode.value)

    // Deactivate app lock
    viewModel.disableAppLock()
    assertFalse(viewModel.isAppLockEnabled.value)
    assertTrue(viewModel.isAppUnlocked.value)
  }
}
