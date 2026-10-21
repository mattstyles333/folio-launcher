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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.folio.launcher.ui.ClockAmPmStyle
import com.folio.launcher.ui.ClockDateStyle
import com.folio.launcher.ui.ClockTimeStyle
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.PrintShadow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun ClockDisplay(
    dim: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    clock: () -> Long = System::currentTimeMillis,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(clock()) }
    // delay() stops while the phone sleeps, so the tick pending at screen-off can still be up to
    // a minute away after unlock. Every start reads the wall clock again and re-arms the tick.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock()
                val ms = 60_000L - (now % 60_000L)
                delay(ms.coerceIn(250L, 60_000L))
            }
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val locale = remember { Locale.getDefault() }
    val datePattern = remember(locale) {
        ClockCopy.datePattern(locale) { DateFormat.getBestDateTimePattern(locale, it) }
    }
    val copy = remember(now, is24, locale, datePattern) {
        ClockCopy.of(now, is24, locale, datePattern = datePattern)
    }
    val timeAlpha = if (dim) 0.72f else 1f
    val dateAlpha = if (dim) 0.54f else 0.90f
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
                style = ClockTimeStyle.copy(shadow = PrintShadow),
                color = PrintInk.copy(alpha = timeAlpha),
                modifier = Modifier.alignByBaseline(),
            )
            if (copy.ampm != null) {
                Text(
                    text = copy.ampm,
                    style = ClockAmPmStyle.copy(shadow = PrintShadow),
                    color = PrintInk.copy(alpha = timeAlpha * 0.86f),
                    modifier = Modifier
                        .alignByBaseline()
                        .padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = copy.date,
            style = ClockDateStyle.copy(shadow = PrintShadow),
            color = PrintInk.copy(alpha = dateAlpha),
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

        /** The house order, kept for English: "Thursday, August 27". */
        const val EnglishDate = "EEEE, MMMM d"

        /**
         * Other languages put the day and month their own way ("Mittwoch, 21. Oktober"). [best] is
         * the platform's best pattern for a skeleton; if java.time can't read it, the house order stays.
         */
        fun datePattern(locale: Locale, best: (skeleton: String) -> String?): String {
            if (locale.language == "en") return EnglishDate
            return runCatching { best("EEEEMMMMd")?.also { DateTimeFormatter.ofPattern(it, locale) } }
                .getOrNull() ?: EnglishDate
        }

        fun of(
            epochMs: Long,
            is24: Boolean,
            locale: Locale,
            zone: ZoneId = ZoneId.systemDefault(),
            datePattern: String = EnglishDate,
        ): ClockCopy {
            val zoned = Instant.ofEpochMilli(epochMs).atZone(zone)
            return of(zoned, is24, locale, datePattern)
        }

        fun of(
            zoned: ZonedDateTime,
            is24: Boolean,
            locale: Locale,
            datePattern: String = EnglishDate,
        ): ClockCopy {
            // Bound per call: on their own the formatters keep the default locale of their first use.
            val timeFmt = (if (is24) Time24 else Time12).withLocale(locale)
            val dateFmt = DateTimeFormatter.ofPattern(datePattern, locale)
            return ClockCopy(
                time = zoned.format(timeFmt),
                ampm = if (is24) null else zoned.format(AmPm.withLocale(locale)).lowercase(locale),
                date = zoned.format(dateFmt),
            )
        }
    }
}
