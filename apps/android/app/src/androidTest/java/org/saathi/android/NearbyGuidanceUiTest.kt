package org.saathi.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NearbyGuidanceUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun directMessagingAndLiveWifiGuidanceAreSeparate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storageScope = "test-network-ui-${UUID.randomUUID()}"
        val models = ViewModelStore()
        lateinit var vm: SaathiViewModel
        instrumentation.runOnMainSync { vm = SaathiViewModel(context.applicationContext as Application, storageScope, false); models.put("fixture", vm) }
        val screen = mutableStateOf(AppState())
        try {
            ui.setContent { SaathiTheme { androidx.compose.material3.Surface(Modifier.fillMaxSize()) { NearbyScreen(vm, screen.value, {}, {}, { _, _ -> }, Modifier.fillMaxSize()) } } }
            ui.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Pair on local Wi-Fi"))
            ui.onNodeWithText("No local Wi-Fi address yet.", substring = true).assertExists()
            ui.onNodeWithText("Hotspot & network settings").performScrollTo().assertIsDisplayed()
            fun capture(name: String) = ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(context.cacheDir, "swarm-network-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            capture("needs-hotspot")
            ui.runOnIdle { screen.value = screen.value.copy(localWifiAddress = true) }
            ui.onNodeWithText("This phone has a local Wi-Fi address.", substring = true).assertExists()
            capture("wifi-address")
        } finally {
            vm.repository.store.clearPrivate()
            instrumentation.runOnMainSync { models.clear() }
            context.deleteDatabase("saathi-$storageScope.db")
        }
    }
}
