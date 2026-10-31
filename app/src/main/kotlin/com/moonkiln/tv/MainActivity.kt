package com.moonkiln.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

// Toolchain probe only: an empty Compose for TV screen to measure the baseline memory on the TV.
// The real screens follow the agreed design.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF5EFE2)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Moon Kiln", color = Color(0xFFB5713C))
                }
            }
        }
    }
}
