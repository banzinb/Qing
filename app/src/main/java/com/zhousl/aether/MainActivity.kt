package com.zhousl.aether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zhousl.aether.ui.AetherApp

class MainActivity : AppCompatActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private var browserHost: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AetherApp(onNotificationPermissionRequested = ::maybeRequestNotificationPermission)
        }
        attachBrowserHost()
    }

    override fun onDestroy() {
        val controller = (application as AetherApplication).runtime.webViewBrowserController
        controller.detachHost()
        if (!isChangingConfigurations) controller.destroyAllTabs()
        browserHost = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        (application as AetherApplication).runtime.nativeModManager.notifyUiStable()
    }

    /**
     * Keeps the embedded browser pool attached to a real window.
     *
     * The container is inserted at index 0 of the content frame, i.e. behind the Compose
     * surface, which is opaque and covers the whole screen. Pooled WebViews therefore render at
     * their true viewport (so layout, visibility and screenshots behave) without ever being
     * visible to the user, and they never intercept touches.
     */
    private fun attachBrowserHost() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val host = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            isFocusable = false
        }
        content.addView(host, 0)
        browserHost = host
        (application as AetherApplication).runtime.webViewBrowserController.attachHost(host)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
