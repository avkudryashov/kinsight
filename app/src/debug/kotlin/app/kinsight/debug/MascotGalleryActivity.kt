package app.kinsight.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinsight.core.designsystem.component.AnimalAvatar
import app.kinsight.core.model.Animal
import app.kinsight.core.model.Mood

/**
 * Debug builds only: every mascot in every mood, for looking at the art (animal avatars plan, Task 2). Rows are the
 * animals, columns the moods (happiest first) at 56 dp, then the same at 32 dp with the animals across. The background
 * is the light theme's; `--ez dark true` gives the dark theme's.
 *
 * `adb shell am start -n app.kinsight/.debug.MascotGalleryActivity [--ez dark true]`
 */
class MascotGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", false)
        setContent { Gallery(dark) }
    }
}

@Composable
private fun Gallery(dark: Boolean) {
    val background = if (dark) Color(0xFF17151F) else Color(0xFFF3F0F9)
    val ink = if (dark) Color(0xFFECE9F7) else Color(0xFF2F3256)
    Column(
        Modifier.fillMaxSize().background(background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("", Modifier.padding(end = 0.dp))
            for (mood in Mood.entries) Text(mood.name.take(5), Modifier.padding(horizontal = 6.dp), color = ink, fontSize = 10.sp)
        }
        for (animal in Animal.entries) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mood in Mood.entries) AnimalAvatar(animal, mood, size = 56.dp)
                Text(animal.name, Modifier.padding(top = 20.dp), color = ink, fontSize = 10.sp)
            }
        }
        Text("32 dp", color = ink, fontSize = 10.sp)
        for (mood in Mood.entries) Line(mood, 32.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { AnimalAvatar(null, size = 32.dp); AnimalAvatar(null, size = 56.dp) }
    }
}

@Composable
private fun Line(mood: Mood, size: Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (animal in Animal.entries) AnimalAvatar(animal, mood, size = size)
    }
}
