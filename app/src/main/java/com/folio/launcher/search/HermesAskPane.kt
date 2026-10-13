package com.folio.launcher.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.launcher.hermes.ExchangeStatus
import com.folio.launcher.hermes.HermesAskState
import com.folio.launcher.hermes.HermesExchange
import com.folio.launcher.ui.PrintInk
import com.folio.launcher.ui.PrintShadow

/** What the Hermes overlay can do. Defaults do nothing, so screens without Hermes need not care. */
class HermesAskActions(
    val ask: (String) -> Unit = {},
    val stop: () -> Unit = {},
    val newSession: () -> Unit = {},
    val approve: (ApprovalChoice) -> Unit = {},
    val clarify: (Map<String, String?>) -> Unit = {},
    /** Start a Hermes Live call; the text is its first request ("" for none). */
    val call: (String) -> Unit = {},
    /** The overlay is on screen with Hermes (true) or gone (false). */
    val visible: (Boolean) -> Unit = {},
)

private val ReplyStyle = TextStyle(
    color = PrintInk,
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Light,
    fontSize = 19.sp,
    lineHeight = 27.sp,
    shadow = PrintShadow,
)

/** The Ask overlay's body when Hermes answers: field + mic, chips or the exchange, approvals, Stop. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HermesAskPane(
    state: HermesAskState,
    query: String,
    onQueryChange: (String) -> Unit,
    suggestions: List<Pair<String, String>>,
    accent: Color,
    focus: FocusRequester,
    actions: HermesAskActions,
    onDismiss: () -> Unit,
) {
    fun submit() {
        if (query.isBlank()) return
        actions.ask(query)
        onQueryChange("")
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Hermes",
                color = PrintInk.copy(alpha = 0.5f),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light,
                fontSize = 28.sp,
                modifier = Modifier.weight(1f),
            )
            if (state.exchanges.isNotEmpty() || state.hasSession) {
                Chip("New") { actions.newSession() }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                textStyle = TextStyle(color = PrintInk, fontSize = 20.sp, fontWeight = FontWeight.Light),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                "Ask Hermes",
                                color = PrintInk.copy(alpha = 0.32f),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            MicButton {
                actions.call(query.trim())
                onQueryChange("")
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.4f)))
        Spacer(Modifier.height(14.dp))
        state.error?.let { Quiet(it) }
        if (state.exchanges.isEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { (label, prompt) ->
                    Chip(label) { if (prompt.isEmpty()) actions.call("") else actions.ask(prompt) }
                }
            }
            state.approval?.let { ApprovalCard(it, accent, actions.approve) }
            state.clarify?.let { ClarifyCard(it, accent, actions.clarify) }
            Spacer(Modifier.weight(1f))
        } else {
            Transcript(state, accent, actions)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.running) {
                Text(
                    "Stop",
                    color = accent.copy(alpha = 0.92f),
                    fontSize = 16.sp,
                    modifier = Modifier.clickable(onClick = actions.stop),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Cancel",
                color = accent.copy(alpha = 0.92f),
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
    }
}

@Composable
private fun ColumnScope.Transcript(state: HermesAskState, accent: Color, actions: HermesAskActions) {
    val scroll = rememberScrollState()
    val last = state.exchanges.last()
    LaunchedEffect(last.id, last.reply.length, state.working, state.approval, state.clarify) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scroll),
    ) {
        state.exchanges.forEach { exchange ->
            Exchange(exchange, thinking = exchange === last && exchange.status == ExchangeStatus.Running)
        }
        state.working?.let { Quiet(it, italic = true) }
        state.approval?.let { ApprovalCard(it, accent, actions.approve) }
        state.clarify?.let { ClarifyCard(it, accent, actions.clarify) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Exchange(exchange: HermesExchange, thinking: Boolean) {
    Text(
        exchange.question,
        color = PrintInk.copy(alpha = 0.55f),
        fontSize = 14.sp,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(6.dp))
    when {
        exchange.reply.isNotEmpty() -> Text(exchange.reply, style = ReplyStyle)
        thinking -> Quiet("Hermes is thinking…", italic = true)
    }
    exchange.note?.let { Quiet(it) }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ApprovalCard(request: ApprovalRequest, accent: Color, onAnswer: (ApprovalChoice) -> Unit) {
    Card {
        Text("Hermes wants to go ahead", color = PrintInk, fontSize = 15.sp)
        if (request.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(request.description, color = PrintInk.copy(alpha = 0.7f), fontSize = 14.sp)
        }
        if (request.command.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                request.command,
                color = PrintInk.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Approve once", fill = accent.copy(alpha = 0.32f)) { onAnswer(ApprovalChoice.Once) }
            Chip("Deny") { onAnswer(ApprovalChoice.Deny) }
        }
    }
}

/** Hermes's questions, one at a time: a choice, free text, or Skip. Sent when all are answered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarifyCard(request: ClarifyRequest, accent: Color, onAnswer: (Map<String, String?>) -> Unit) {
    val answers = remember(request.id) { mutableStateMapOf<String, String?>() }
    val picked = remember(request.id) { mutableStateListOf<String>() }
    var typed by remember(request.id) { mutableStateOf("") }
    val question = request.questions.firstOrNull { it.qid !in answers } ?: return
    fun answer(value: String?) {
        answers[question.qid] = value
        picked.clear()
        typed = ""
        if (request.questions.all { it.qid in answers }) onAnswer(answers.toMap())
    }
    Card {
        Text(question.question, color = PrintInk, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        if (question.choices.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.choices.forEach { choice ->
                    val on = choice in picked
                    Chip(choice, fill = if (on) accent.copy(alpha = 0.32f) else null) {
                        if (!question.multiSelect) {
                            answer(choice)
                        } else if (on) {
                            picked.remove(choice)
                        } else {
                            picked.add(choice)
                        }
                    }
                }
            }
        } else {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                textStyle = TextStyle(color = PrintInk, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (typed.isNotBlank()) answer(typed.trim()) }),
                decorationBox = { inner ->
                    Box {
                        if (typed.isEmpty()) Text("Your answer", color = PrintInk.copy(alpha = 0.32f), fontSize = 16.sp)
                        inner()
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (question.multiSelect && picked.isNotEmpty()) {
                Chip("Done", fill = accent.copy(alpha = 0.32f)) { answer(picked.joinToString(", ")) }
            }
            if (question.choices.isEmpty() && typed.isNotBlank()) {
                Chip("Send", fill = accent.copy(alpha = 0.32f)) { answer(typed.trim()) }
            }
            Chip("Skip") { answer(null) }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun Chip(label: String, fill: Color? = null, onClick: () -> Unit) {
    Text(
        text = label,
        color = PrintInk.copy(alpha = 0.88f),
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(fill ?: Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun Quiet(text: String, italic: Boolean = false) {
    Text(
        text,
        color = PrintInk.copy(alpha = 0.5f),
        fontSize = 13.sp,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A thin-stroke microphone in PrintInk: opens a Hermes Live call. */
@Composable
private fun MicButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .semantics {
                contentDescription = "Talk to Hermes"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = 1.5.dp.toPx()
            val w = size.width
            val h = size.height
            val capsuleW = w * 0.36f
            val capsuleH = h * 0.56f
            drawRoundRect(
                color = PrintInk,
                topLeft = Offset((w - capsuleW) / 2f, 0f),
                size = Size(capsuleW, capsuleH),
                cornerRadius = CornerRadius(capsuleW / 2f),
                style = Stroke(stroke),
            )
            val arcW = w * 0.64f
            drawArc(
                color = PrintInk,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset((w - arcW) / 2f, capsuleH * 0.45f),
                size = Size(arcW, capsuleH * 0.75f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(
                color = PrintInk,
                start = Offset(w / 2f, capsuleH * 0.45f + capsuleH * 0.75f),
                end = Offset(w / 2f, h),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
