package com.streamnest.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.streamnest.tv.ui.nav.AppRoot
import com.streamnest.tv.ui.theme.StreamNestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StreamNestTheme {
                AppRoot()
            }
        }
    }
}
