package com.orion

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.orion.databinding.ActivityFlagSecureBinding

class FlagSecureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlagSecureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityFlagSecureBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
