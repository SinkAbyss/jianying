package com.sinkabyss.framewatermark

import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavingProgressDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savingWindowBlocksBackgroundTapsAndBackThenRestoresEditing() {
        val saving = mutableStateOf(false)
        var changes = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { changes++ }) { Text("Change editing settings") }
                    }
                    if (saving.value) SavingProgressDialog()
                }
            }
        }
        compose.onNodeWithText("Change editing settings").performClick()
        assertEquals(1, changes)
        compose.runOnUiThread { saving.value = true }
        compose.onNodeWithText("正在保存…").assertExists()
        compose.onNode(isDialog()).performTouchInput { click(center) }
        assertEquals("Tap must stay in the modal window", 1, changes)
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("正在保存…").assertExists()
        assertTrue(dialog.isShowing)
        // Keep test evidence inside the Android build tree: writing to the repository root left an
        // untracked, non-ignored directory behind on every test run.
        val folder = File(System.getProperty("c1.workspace"), "android/app/build/test-evidence/saving-dialog")
            .apply { mkdirs() }
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(folder, "saving-dialog.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.runOnUiThread { saving.value = false }
        compose.onNodeWithText("正在保存…").assertDoesNotExist()
        compose.onNodeWithText("Change editing settings").performClick()
        assertEquals(2, changes)
    }
}
