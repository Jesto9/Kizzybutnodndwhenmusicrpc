package kizzy.gateway

import com.my.kizzy.domain.interfaces.Logger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.*
import io.ktor.serialization.gson.*
import io.ktor.websocket.*
import kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import kizzy.gateway.entities.Payload
import kizzy.gateway.entities.Resume
import kizzy.gateway.entities.op.OpCodes
import kizzy.gateway.entities.op.OpCodes.*
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import com.my.kizzy.domain.interfaces.NoOpLogger

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var connected = false
    private var client: HttpClient = HttpClient {
        install(WebSockets) {
            contentConverter = GsonWebsocketContentConverter()
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.Default

    override suspend fun connect() {
        launch {
            try {
                logger.i("Gateway","Connect called")
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)

                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect {
                        when (it) {
                            is Frame.Text -> onMessage(websocket!!.converter?.deserialize(it))
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.e("Gateway",e.message?:"")
                close()
            }
        }
    }

    private suspend fun handleClose(){
        heartbeatJob?.cancel()
        connected = false
        val close = websocket?.closeReason?.await()
        logger.w("Gateway","Closed with code: ${close?.code}, " +
                "reason: ${close?.message}, " +
                "can_reconnect: ${close?.code?.toInt() == 4000}")
        if (close?.code?.toInt() == 4000) {
            delay(200.milliseconds)
            connect()
        } else
            close()
    }

    private suspend fun onMessage(payload: Payload?) {
        if (payload == null)
            return
        logger.d("Gateway","Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch()
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> payload.handleHello()
            else -> {}
        }
    }

    open fun Payload.handleDispatch() {
        when (this.t.toString()) {
            "READY" -> {
                sessionId = (this.d as Map<*, *>?)!!["session_id"].toString()
                resumeGatewayUrl = this.d!!["resume_gateway_url"].toString() + "/?v=10&encoding=json"
                logger.i("Gateway","resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway","session_id updated to $sessionId")
                connected = true
                return
            }
            "RESUMED" -> {
                logger.i("Gateway","Session Resumed")
            }
            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.i("Gateway","Handling Invalid Session")
        logger.d("Gateway","Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun Payload.handleHello() {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = ((this.d as Map<*, *>)["heartbeat_interval"] as Double).toLong()
        logger.i("Gateway","Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway","Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway","Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload()
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway","Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            while (isActive) {
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isWebSocketConnected(): Boolean {
        return websocket?.incoming != null && websocket?.outgoing?.isClosedForSend == false
    }


    private suspend fun send(op: OpCodes, d: Any?) {
        if (websocket?.isActive == true) {
            websocket?.sendSerialized(
                Payload(
                    op = op,
                    d = d
                )
            )
        }
    }

    override fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        this.cancel()
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        runBlocking {
            websocket?.close()
            logger.e("Gateway","Connection to gateway closed")
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        // TODO : Figure out a better way to wait for socket to be connected to account
        while (!isSocketConnectedToAccount()){
            delay(10.milliseconds)
        }
        logger.i("Gateway","Sending $PRESENCE_UPDATE")
        send(
            op = PRESENCE_UPDATE,
            d = presence
        )
    }

}