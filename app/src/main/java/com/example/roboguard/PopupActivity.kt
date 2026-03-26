package com.example.roboguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A lightweight, transient Activity used to display floating notifications to the user.
 * It appears at the top of the screen and automatically dismisses itself after a set duration.
 */
class PopupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the notification message from the starting Intent
        val message = intent.getStringExtra("message") ?: "Notification"

        // 2. Create and configure a simple TextView UI
        val textView = TextView(this).apply {
            text = message
            textSize = 20f
            setPadding(50, 30, 50, 30)
            setBackgroundColor(0xFFEEEEEE.toInt()) // Light Gray background
            setTextColor(0xFF000000.toInt())     // Black text
            gravity = Gravity.CENTER
        }
        setContentView(textView)

        // 3. Configure the window to behave like a floating popup at the top
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.TOP)

        // Make the activity background transparent to give it a "floating" look
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // 4. Auto-close (finish) the activity after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 5000)
    }
}
