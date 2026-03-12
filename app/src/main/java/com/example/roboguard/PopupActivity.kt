package com.example.roboguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class PopupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the message from the Intent
        val message = intent.getStringExtra("message") ?: "Notification"

        // 2. Create a simple UI
        val textView = TextView(this).apply {
            text = message
            textSize = 20f
            setPadding(50, 30, 50, 30)
            setBackgroundColor(0xFFEEEEEE.toInt()) // Light Gray
            setTextColor(0xFF000000.toInt())     // Black
            gravity = Gravity.CENTER
        }
        setContentView(textView)

        // 3. Make it look like a floating popup at the top
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.TOP)

        // Optional: Make background transparent so it doesn't look like a full app
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        // 4. Auto-close after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 5000)
    }
}