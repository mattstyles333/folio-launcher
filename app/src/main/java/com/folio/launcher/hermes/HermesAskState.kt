package com.folio.launcher.hermes

import com.folio.hermes.ApprovalRequest
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayConnection
import com.folio.hermes.GatewayUnavailableException
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesRpcException
import com.folio.hermes.SubmitStatus
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import java.io.IOException

enum class ExchangeStatus { Running, Done, Stopped, Failed }

/** One question in the Ask overlay and Hermes's reply so far. */
data class HermesExchange(
    val id: Long,
    val question: String,
    val reply: String = "",
    val status: ExchangeStatus = ExchangeStatus.Running,
    /** A quiet line under the reply: why it stopped, or a warning, in plain words. */
    val note: String? = null,
)

/** What the Hermes Ask overlay shows. */
data class HermesAskState(
    val exchanges: List<HermesExchange> = emptyList(),
    /** "Hermes is working: web search", while a tool runs. */
    val working: String? = null,
    /** Connection trouble in plain words; null when fine. */
    val error: String? = null,
    /** Waiting for the user's yes; never answered automatically. */
    val approval: ApprovalRequest? = null,
    val clarify: ClarifyRequest? = null,
    /** A Hermes session is stored (so "New" means something). */
    val hasSession: Boolean = false,
) {
    val running: Boolean get() = exchanges.lastOrNull()?.status == ExchangeStatus.Running
}

/** Pure state changes for [HermesAsk]; unit-tested without a server. */
object HermesAskReducer {
    const val MAX_EXCHANGES = 20

    fun asked(state: HermesAskState, id: Long, question: String): HermesAskState {
        val settled = state.exchanges.map {
            if (it.status == ExchangeStatus.Running) it.copy(status = ExchangeStatus.Stopped) else it
        }
        return state.copy(
            exchanges = (settled + HermesExchange(id, question)).takeLast(MAX_EXCHANGES),
            working = null,
            error = null,
        )
    }

    fun reduce(state: HermesAskState, id: Long, update: TurnUpdate): HermesAskState = when (update) {
        is TurnUpdate.Accepted -> if (update.status == SubmitStatus.Queued) {
            state.copy(working = "Waiting for the last question to finish")
        } else {
            state
        }
        TurnUpdate.Started -> state.copy(working = null)
        is TurnUpdate.Text -> state.edit(id) { it.copy(reply = update.text) }
        is TurnUpdate.Interim -> state.copy(working = update.text.lineSequence().firstOrNull()?.trim()?.take(140)?.ifEmpty { null })
        is TurnUpdate.ToolStarted -> state.copy(working = "Hermes is working: ${update.label}")
        is TurnUpdate.ToolCompleted -> state.copy(working = null)
        is TurnUpdate.ApprovalNeeded -> request(state, update.request)
        is TurnUpdate.ClarifyNeeded -> request(state, update.request)
        is TurnUpdate.RequestWithdrawn -> withdrawn(state, update.id)
        is TurnUpdate.Warning -> state.edit(id) { it.copy(note = update.message.ifBlank { null }) }
        is TurnUpdate.Completed -> state.copy(working = null, approval = null, clarify = null).edit(id) {
            val reply = update.text.ifEmpty { it.reply }
            when (update.status) {
                TurnStatus.Complete -> it.copy(reply = reply, status = ExchangeStatus.Done)
                TurnStatus.Interrupted -> it.copy(reply = reply, status = ExchangeStatus.Stopped, note = "Stopped.")
                TurnStatus.Error -> it.copy(
                    reply = reply,
                    status = ExchangeStatus.Failed,
                    note = update.error?.takeIf { e -> e.isNotBlank() }?.let { e -> "Hermes hit a problem: $e" }
                        ?: "Hermes hit a problem and stopped.",
                )
            }
        }
        is TurnUpdate.Failed -> failed(state, id, update.cause?.let(::plain) ?: update.message.ifBlank { REACH })
    }

    fun failed(state: HermesAskState, id: Long, message: String): HermesAskState =
        state.copy(working = null).edit(id) { it.copy(status = ExchangeStatus.Failed, note = message) }

    /** The overlay closed while Hermes was still answering. */
    fun closed(state: HermesAskState): HermesAskState = state.copy(
        working = null,
        exchanges = state.exchanges.map {
            if (it.status == ExchangeStatus.Running) {
                it.copy(status = ExchangeStatus.Stopped, note = "Closed before Hermes finished. The rest is in your Hermes session.")
            } else {
                it
            }
        },
    )

    /** Back after [closed] with that turn still going: running again, its partial reply kept until Completed. */
    fun resumed(state: HermesAskState, id: Long): HermesAskState =
        state.copy(working = "Hermes is still answering…").edit(id) { it.copy(status = ExchangeStatus.Running, note = null) }

    /** Stop couldn't reach Hermes: exchange [id] stops, but only while it is still the one running. */
    fun stopped(state: HermesAskState, id: Long?): HermesAskState {
        if (state.exchanges.none { it.id == id && it.status == ExchangeStatus.Running }) return state
        return state.copy(
            working = null,
            exchanges = state.exchanges.map {
                if (it.id == id) it.copy(status = ExchangeStatus.Stopped, note = "Stopped.") else it
            },
        )
    }

    fun request(state: HermesAskState, request: ApprovalRequest): HermesAskState = state.copy(approval = request)

    fun request(state: HermesAskState, request: ClarifyRequest): HermesAskState = state.copy(clarify = request)

    fun withdrawn(state: HermesAskState, requestId: String): HermesAskState = state.copy(
        approval = state.approval?.takeUnless { it.id == requestId },
        clarify = state.clarify?.takeUnless { it.id == requestId },
    )

    /** The connection line under the title, or null when there is nothing to say. */
    fun connectionLine(connection: GatewayConnection): String? = when (connection) {
        is GatewayConnection.Stopped -> if (connection.signInRequired) {
            connection.reason.ifBlank { LiveTokenProvider.SIGNED_OUT }
        } else {
            "Hermes turned the connection down. Try again later."
        }
        // Off the tailnet the gateway waits for Tailscale: say so, whatever the attempt.
        is GatewayConnection.Waiting -> when {
            connection.reason == LiveTokenProvider.TAILNET -> LiveTokenProvider.TAILNET
            connection.attempt >= 2 -> "Can’t reach Hermes. Trying again…"
            else -> null
        }
        else -> null
    }

    /** An exception in plain words. */
    fun plain(e: Throwable): String = when (e) {
        is HermesAuthException.SignInRequired -> e.message?.takeIf { it.isNotBlank() } ?: LiveTokenProvider.SIGNED_OUT
        is HermesAuthException -> "Hermes didn’t accept Hermes Live’s session. Open Hermes Live and try again."
        is HermesRpcException -> when {
            e.isSessionBusy -> "Hermes is still busy with the last question."
            e.isSessionNotOwned -> "This Hermes session is open somewhere else. Tap New."
            else -> "Hermes couldn’t take that (code ${e.code})."
        }
        is GatewayUnavailableException -> REACH
        is IOException -> REACH
        else -> "Something went wrong talking to Hermes."
    }

    const val REACH = "Can’t reach Hermes. Check the connection and try again."

    private inline fun HermesAskState.edit(id: Long, change: (HermesExchange) -> HermesExchange): HermesAskState =
        copy(exchanges = exchanges.map { if (it.id == id) change(it) else it })
}
