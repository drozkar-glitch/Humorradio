package cz.taximares.humorradio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        setContent {
            val isPlaying by RadioService.isPlaying.collectAsState()

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                RadioScreen(
                    isPlaying = isPlaying,
                    onTogglePlay = {
                        val serviceIntent = Intent(this, RadioService::class.java)
                        if (isPlaying) {
                            serviceIntent.action = RadioService.ACTION_STOP
                            startService(serviceIntent)
                        } else {
                            serviceIntent.action = RadioService.ACTION_PLAY
                            ContextCompat.startForegroundService(this, serviceIntent)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun RadioScreen(isPlaying: Boolean, onTogglePlay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Rádio Humor",
            color = Color(0xFFFFD700),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (isPlaying) "Hraje (běží i při zhasnutém displeji)" else "Vypnuto",
            color = if (isPlaying) Color(0xFF4CAF50) else Color.Gray,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(56.dp))

        Button(
            onClick = onTogglePlay,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPlaying) Color(0xFFE53935) else Color(0xFF4CAF50)
            ),
            modifier = Modifier.size(180.dp)
        ) {
            Text(
                text = if (isPlaying) "VYPNOUT" else "SPUSTIT",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
