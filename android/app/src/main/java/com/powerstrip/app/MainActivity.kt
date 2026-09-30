package com.powerstrip.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Server.init(this)
        Lang.init(this)
        Lang.applyStored(this)
        setContent {
            PowerStripTheme {
                PowerStripApp()
            }
        }
    }
}
