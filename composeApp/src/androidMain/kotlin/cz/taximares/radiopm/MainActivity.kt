package cz.taximares.radiopm

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        RadioController.onPickedAudiobookFiles(uris)
    }

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        RadioController.onPickedAudiobookFolder(treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RadioController.init(applicationContext)
        RadioController.bindFilePickers(
            launchFiles = {
                pickFilesLauncher.launch(
                    arrayOf(
                        "audio/*",
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            },
            launchFolder = {
                pickFolderLauncher.launch(null)
            }
        )
        setContent {
            App()
        }
    }

    override fun onDestroy() {
        RadioController.unbindFilePickers()
        super.onDestroy()
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val delta = -ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) {
                RadioController.onRotaryScroll(delta)
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            RadioController.syncVolumePercent()
        }
        return handled
    }
}
