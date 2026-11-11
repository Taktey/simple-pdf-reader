package com.taktey.simplepdfreader

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun welcomeOpensPicker() {
        var opened = false
        compose.setContent { ReaderTheme { ReaderApp(ReaderState.Empty, { opened = true }, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Открыть PDF").assertIsDisplayed().performClick()
        assertTrue(opened)
        screenshot("welcome")
    }

    @Test fun readerCanNavigateZoomAndShare() {
        val session = PreviewPdfSource(samplePdf(RuntimeEnvironment.getApplication()))
        var shared = false
        compose.setContent { ReaderTheme {
            ReaderApp(ReaderState.Ready(session, 0, 0), {}, {}, { _, _ -> }, { shared = it === session })
        } }
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Страница 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Поделиться").performClick()
        assertTrue(shared)
        compose.onNodeWithContentDescription("Увеличить").performClick()
        compose.onNodeWithText("125%").assertIsDisplayed()
        compose.onNodeWithText("По ширине").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующая страница").performClick()
        compose.onNodeWithText("2 из 3").assertIsDisplayed()
        compose.onNodeWithText("2 из 3").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("3")
        compose.onNodeWithText("Перейти", useUnmergedTree = true).performClick()
        compose.onNodeWithText("3 из 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующая страница").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Предыдущая страница").performClick()
        compose.onNodeWithContentDescription("Предыдущая страница").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Страница 1").fetchSemanticsNodes().isNotEmpty() }
        screenshot("reader")
        // The composition owns asynchronous page requests until the rule disposes it.
    }

    @Test fun invalidDocumentOffersAnotherFile() {
        var opened = false
        compose.setContent { ReaderTheme {
            ReaderApp(ReaderState.Error(R.string.error_invalid), { opened = true }, {}, { _, _ -> }, {})
        } }
        compose.onNodeWithText("Не удалось открыть PDF").assertIsDisplayed()
        compose.onNodeWithText("Открыть другой PDF").performClick()
        assertTrue(opened)
        screenshot("error")
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi")
    fun welcomeRemainsUsableInLandscape() {
        var opened = false
        compose.setContent { ReaderTheme { ReaderApp(ReaderState.Empty, { opened = true }, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Открыть PDF").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(opened)
        screenshot("welcome-landscape")
    }

    private fun screenshot(name: String) {
        val file = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
