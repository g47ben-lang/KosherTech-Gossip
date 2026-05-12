package com.koshertech.gossip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val messagesList = mutableListOf<String>()
    private lateinit val adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // יצירת ממשק בסיסי מקוד כדי לחסוך קבצי XML בשלב הראשון
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val inputField = EditText(this).apply {
            hint = "הקלד הודעה קצרה (עד 140 תווים)..."
            filters = arrayOf(android.text.InputFilter.LengthFilter(140))
        }

        val sendButton = Button(this).apply {
            text = "שדר לכולם"
            setOnClickListener {
                val text = inputField.text.toString()
                if (text.isNotBlank()) {
                    sendMessageToService(text)
                    inputField.text.clear()
                }
            }
        }

        val listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messagesList)
        listView.adapter = adapter

        layout.addView(inputField)
        layout.addView(sendButton)
        layout.addView(listView)
        setContentView(layout)

        checkPermissionsAndStartService()
    }

    private fun checkPermissionsAndStartService() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startGossipService()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
    }

    private fun startGossipService() {
        val intent = Intent(this, GossipService::class.java)
        startService(intent)
    }

    private fun sendMessageToService(text: String) {
        // נוסיף לרשימה שלנו בינתיים
        messagesList.add("אני: $text")
        adapter.notifyDataSetChanged()
        
        // שליחה לשירות שיפיץ הלאה ב-BLE
        val intent = Intent(this, GossipService::class.java).apply {
            action = "SEND_MESSAGE"
            putExtra("MESSAGE_TEXT", text)
        }
        startService(intent)
    }
}
