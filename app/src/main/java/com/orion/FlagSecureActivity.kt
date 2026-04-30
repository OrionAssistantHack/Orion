package com.orion

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.orion.databinding.ActivityFlagSecureBinding

class FlagSecureActivity : AppCompatActivity() {

    private val TAG = "OrionFlagSecure"

    private lateinit var binding: ActivityFlagSecureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate — FLAG_SECURE set")
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityFlagSecureBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
