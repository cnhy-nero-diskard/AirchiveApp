package cloud.airchive.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val InstrumentLight = lightColorScheme(
    primary = Color(0xFF315EAA), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EBFA), onPrimaryContainer = Color(0xFF16396F),
    secondary = Color(0xFF53666F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5ECF0), onSecondaryContainer = Color(0xFF202D34),
    background = Color(0xFFF1F4F5), onBackground = Color(0xFF202D34),
    surface = Color.White, onSurface = Color(0xFF202D34),
    surfaceVariant = Color(0xFFE8EEF2), onSurfaceVariant = Color(0xFF53666F),
    surfaceContainer = Color(0xFFF1F4F5), surfaceContainerLow = Color.White,
    surfaceContainerHighest = Color(0xFFE8EEF2), outline = Color(0xFF71838E),
    outlineVariant = Color(0xFFD4DDE3), surfaceTint = Color(0xFF315EAA),
    tertiary = Color(0xFF8C4B00), tertiaryContainer = Color(0xFFFFF4E4), onTertiaryContainer = Color(0xFF8C4B00),
)
private val InstrumentDark = darkColorScheme(
    primary = Color(0xFFABC7FF), onPrimary = Color(0xFF092A60),
    primaryContainer = Color(0xFF244675), onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFB4C3CD), onSecondary = Color(0xFF1A252D),
    secondaryContainer = Color(0xFF2C3D49), onSecondaryContainer = Color(0xFFE6EDF2),
    background = Color(0xFF111A21), onBackground = Color(0xFFE6EDF2),
    surface = Color(0xFF1A252D), onSurface = Color(0xFFE6EDF2),
    surfaceVariant = Color(0xFF2C3D49), onSurfaceVariant = Color(0xFFB4C3CD),
    surfaceContainer = Color(0xFF111A21), surfaceContainerLow = Color(0xFF1A252D),
    surfaceContainerHighest = Color(0xFF2C3D49), outline = Color(0xFF8C9CA7),
    outlineVariant = Color(0xFF40515D), surfaceTint = Color(0xFFABC7FF),
    tertiary = Color(0xFFF2BE71), tertiaryContainer = Color(0xFF3D2E18), onTertiaryContainer = Color(0xFFF2BE71),
)

internal val InstrumentReading = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
    fontSize = 76.sp, lineHeight = 82.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum",
)

/** Home Instrument roles apply to every destination, including blocked/setup states. */
@Composable
fun AirchiveTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) InstrumentDark else InstrumentLight,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
        ),
        content = content,
    )
}

@Composable
fun InstrumentNavigation(selectedIndex: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        ShellDestination.entries.forEachIndexed { index, destination ->
            NavigationBarItem(selected = selectedIndex == index, onClick = { onSelect(index) },
                icon = { DestinationGlyph(destination, selectedIndex == index) },
                label = { Text(listOf("Home", "Device", "Energy", "History", "Settings")[index]) })
        }
    }
}
