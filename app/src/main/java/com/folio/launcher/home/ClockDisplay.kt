package com.folio.launcher.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.folio.launcher.ui.ClockAmPmStyle
import com.folio.launcher.ui.ClockDateStyle
import com.folio.launcher.ui.ClockTimeStyle
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun ClockDisplay(
    accent: Color,
    dim: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val ms = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(ms.coerceIn(250L, 60_000L))
            now = System.currentTimeMillis()
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val locale = remember { Locale.getDefault() }
    val copy = remember(now, is24, locale) {
        ClockCopy.of(now, is24, locale)
    }
    val timeAlpha = if (dim) 0.40f else 0.94f
    val dateAlpha = if (dim) 0.26f else 0.72f
    val shadow = Shadow(
        color = Color.Black.copy(alpha = if (dim) 0.35f else 0.70f),
        offset = Offset(0f, 1.2f),
        blurRadius = 2.4f,
    )
    Column(
        modifier
            .pointerInput(onTap, onLongPress) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onTap() },
                )
            }
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            Text(
                text = copy.time,
                style = ClockTimeStyle.copy(shadow = shadow),
                color = accent.copy(alpha = timeAlpha),
                modifier = Modifier.alignByBaseline(),
            )
            if (copy.ampm != null) {
                Text(
                    text = copy.ampm,
                    style = ClockAmPmStyle.copy(shadow = shadow),
                    color = accent.copy(alpha = timeAlpha * 0.78f),
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = copy.date,
            style = ClockDateStyle.copy(shadow = shadow),
            color = accent.copy(alpha = dateAlpha),
        )
    }
}

data class ClockCopy(
    val time: String,
    val ampm: String?,
    val date: String,
) {
    companion object {
        private val Time12 = DateTimeFormatter.ofPattern("h:mm")
        private val Time24 = DateTimeFormatter.ofPattern("HH:mm")
        private val AmPm = DateTimeFormatter.ofPattern("a")

        fun of(
            epochMs: Long,
            is24: Boolean,
            locale: Locale,
            zone: ZoneId = ZoneId.systemDefault(),
        ): ClockCopy {
            val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
            return of(zoned, is24, locale)
        }

        fun of(zoned: ZonedDateTime, is24: Boolean, locale: Locale): ClockCopy {
            val timeFmt = if (is24) Time24 else Time12
            val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)
            return ClockCopy(
                time = zoned.format(timeFmt),
                ampm = if (is24) null else zoned.format(AmPm).lowercase(locale),
                date = zoned.format(dateFmt),
            )
        }
    }
}
