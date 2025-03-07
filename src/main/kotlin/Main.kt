import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.TwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.enums.CommandPermission
import com.github.twitch4j.eventsub.domain.PredictionStatus
import com.github.twitch4j.eventsub.domain.RedemptionStatus
import com.github.twitch4j.helix.TwitchHelix
import com.github.twitch4j.helix.TwitchHelixBuilder
import com.github.twitch4j.helix.domain.*
import com.github.twitch4j.pubsub.domain.PredictionOutcome
import com.github.twitch4j.pubsub.events.PredictionCreatedEvent
import com.github.twitch4j.pubsub.events.PredictionUpdatedEvent
import com.github.twitch4j.pubsub.events.RewardRedeemedEvent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.sql.DriverManager
import java.time.Duration
import java.util.*

val logger: Logger = LoggerFactory.getLogger("bot")
val dotenv = Dotenv.load()

//  TODO(@olegsvs): fix chars, WTF
val tgAdminID = dotenv.get("TG_ADMIN_ID").replace("'", "")
val tgBotToken = dotenv.get("TG_BOT_TOKEN").replace("'", "")

fun readDodoPromo(): String = File("dodo").readLines().first()

fun writeDodoPromo(newPromo: String) = File("dodo").writeText(newPromo)

var dodoPromo = readDodoPromo()
const val rewardDodoTitle = "Промокод на ДОДО пиццу за 1р (ТОЛЬКО РФ)"
const val rewardTestTitle = "Test whisper"
const val rewardDodoDescription =
    "Получите в личку на твиче промокод на среднюю(30 см) пиццу! Награда может быть активирована 1 раз во время стрима"
var rewardDodoID: String? = null
var rewardTestID: String? = null

val staregeBotAccessToken = dotenv.get("SENTRY_OAUTH_TOKEN").replace("'", "")
//val staregeBotRefreshToken = dotenv.get("SENTRY_REFRESH_TOKEN").replace("'", "")
//val staregeBotTokenExpiresInSeconds = dotenv.get("SENTRY_EXPIRES_IN").replace("'", "")

val twitchChannelAccessToken = dotenv.get("CHANNEL_OAUTH_TOKEN").replace("'", "")
//val twitchChannelRefreshToken = dotenv.get("CHANNEL_REFRESH_TOKEN").replace("'", "")
//val twitchChannelTokenExpiresInSeconds = dotenv.get("CHANNEL_EXPIRES_IN").replace("'", "")

//val tokensGeneratedTimeMillis = System.currentTimeMillis() / 1000
const val testDefaultRefreshRateTokensTimeMillis: Long = 10800 * 1000 // 3h

private val gsonPretty: Gson = GsonBuilder().setPrettyPrinting().create()
val commands = Commands.init()

val staregeBotOAuth2Credential = OAuth2Credential("twitch", staregeBotAccessToken)
val twitchChannelOAuth2Credential = OAuth2Credential("twitch", twitchChannelAccessToken)

val youtubeApiKey = dotenv.get("YOUTUBE_API_KEY").replace("'", "")
val youtubeChannelKey = dotenv.get("YOUTUBE_CHANNEL_KEY").replace("'", "")
val youtubeCommandTriggers = listOf("!yt", "!ютуб", "!youtube")

val broadcasterId = dotenv.get("BROADCASTER_ID").replace("'", "")
val broadcasterIdLR = dotenv.get("BROADCASTER_ID_LR").replace("'", "")
val moderatorId = dotenv.get("MODERATOR_ID").replace("'", "")

val twitchClientId = dotenv.get("TWITCH_CLIENT_ID").replace("'", "")
val twitchClientSecret = dotenv.get("TWITCH_CLIENT_SECRET").replace("'", "")
val MySQL_HOST = dotenv.get("MYSQL_HOST").replace("'", "")
val MySQL_PORT = dotenv.get("MySQL_PORT").replace("'", "")
val MySQL_USER = dotenv.get("MySQL_USER").replace("'", "")
val MySQL_PASSWORD = dotenv.get("MySQL_PASSWORD").replace("'", "")


val helixClient: TwitchHelix = TwitchHelixBuilder.builder()
    .withClientId(twitchClientId)
    .withClientSecret(twitchClientSecret)
    .withLogLevel(feign.Logger.Level.BASIC)
    .build()

val twitchClient: TwitchClient = TwitchClientBuilder.builder()
    .withEnableChat(true)
    .withChatAccount(staregeBotOAuth2Credential)
    .withEnableHelix(true)
    .withEnablePubSub(true)
    .withClientId(twitchClientId)
    .withClientSecret(twitchClientSecret)
    .withFeignLogLevel(feign.Logger.Level.BASIC)
    .withDefaultEventHandler(SimpleEventHandler::class.java)
    .build()

val httpClient = HttpClient(CIO) {
    expectSuccess = true
    install(Logging)
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }
}

val tgBot = bot {
    token = tgBotToken
    dispatch {
        command("ping") {
            val result = bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Pong!")
            result.fold({
                logger.info("On ping command")
            }, {
                logger.info("On ping command, error: $it")
            })
        }
        command("current") {
            if (isTgAdmin(message.chat.id)) {
                val result =
                    bot.sendMessage(chatId = ChatId.fromId(message.chat.id), text = "Current promo: $dodoPromo")
                result.fold({
                    logger.info("On current command")
                }, {
                    logger.info("On current command, error: $it")
                })
            }
        }
        command("update") {
            if (isTgAdmin(message.chat.id)) {
                message.text?.let {
                    val newPromo = it.removePrefix("/update ")
                    dodoPromo = newPromo
                    writeDodoPromo(newPromo)
                    val result = bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = "Value updated to ${readDodoPromo()}!"
                    )
                    result.fold({
                        logger.info("On update command")
                    }, {
                        logger.info("On update command, error: $it")
                    })
                }
            }
        }
    }
}

private fun isTgAdmin(callerId: Long): Boolean {
    return callerId == tgAdminID.toLong()
}

val chatMessages: MutableList<ChannelMessageEvent> = mutableListOf()

data class DatabaseCommand(
    val command: String,
    var text: String,
    var delaySeconds: Int,
    val isEnabled: Boolean,
) {}

val databaseCommands: MutableList<DatabaseCommand> = mutableListOf()
val conn =
    DriverManager.getConnection("jdbc:mysql://${MySQL_HOST}:${MySQL_PORT}/twitch_starege_bot?user=${MySQL_USER}&password=${MySQL_PASSWORD}");

val users = Users.init(
    MySQL_HOST,
    MySQL_PORT,
    MySQL_PASSWORD,
    MySQL_USER
)


@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    logger.info("Bot started")
    tgBot.startPolling()
    Timer().scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            refreshTokensTask()
        }
    }, testDefaultRefreshRateTokensTimeMillis, testDefaultRefreshRateTokensTimeMillis)

    val stmt = conn.createStatement();
    val rs = stmt.executeQuery("SELECT * from text_commands");
    while (rs.next()) {
        databaseCommands.add(
            DatabaseCommand(
                command = rs.getString(2),
                text = rs.getString(3),
                delaySeconds = rs.getInt(4),
                isEnabled = rs.getBoolean(5),
            )
        )
    }

    twitchClient.chat.joinChannel("c_a_k_e")

    twitchClient.chat.joinChannel("lady_rockycat")
    twitchClient.eventManager.onEvent(ChannelMessageEvent::class.java) { event ->
        chatMessages.add(event)
        if (event.channel.name.lowercase() == "lady_rockycat") {
            if (event.message.equals("!sping") || event.message.startsWith("!sping ")) {
                try {
                    event.reply(
                        twitchClient.chat,
                        "Starege pong, доступные команды: !fight, !fstats"
                    )
                } catch (e: Throwable) {
                    logger.error("Failed pingCommand: ", e)
                }
            }
            if (event.message.equals("!fight") || event.message.startsWith("!fight ")) {
                GlobalScope.launch {
                    startDuelCommandLR(event)
                }
            }
            if (event.message.equals("!gof") || event.message.startsWith("!gof ")) {
                GlobalScope.launch {
                    assignDuelCommandLR(event)
                }
            }
            if (event.message.equals("!fstats") || event.message.startsWith("!fstats ")) {
                duelStatsCommandLR(event)
            }
        } else {
            if (event.message.contains("hamster", ignoreCase = true) && event.message.contains(
                    "bot",
                    ignoreCase = true
                )
            ) {
                try {
                    logger.error("ban hamster: Try ban user: " + event.message + " " + event.user.id)
                    helixClient.banUser(
                        staregeBotOAuth2Credential.accessToken,
                        broadcasterId,
                        moderatorId,
                        BanUserInput()
                            .withUserId(event.user.id)
                            .withReason("Messages contains banned phrase 'hamster bot'")
                    ).execute()
                    logger.info("Banned user ${event.user.name}, reason: phrase 'hamster bot'")
                } catch (e: Throwable) {
                    logger.error("ban hamster error: Failed ban user: ", e)
                }
            }
            if (event.message.startsWith("!sbanp ")) {
                banUsersWithPhrase(event, event.message.removePrefix("!sbanp "))
            }
            if (event.message.startsWith("!title ")) {
                changeTitle(event, event.message.removePrefix("!title "))
            }
            if (event.message.startsWith("!sgame ")) {
                changeCategory(event, event.message.removePrefix("!sgame "))
            }
            if (youtubeCommandTriggers.contains(event.message.trim())) {
                GlobalScope.launch {
                    getLastYoutubeHighlight(event)
                }
            }
            if (event.message.equals("!sping") || event.message.startsWith("!sping ")) {
                pingCommand(event)
            }
            if (event.message.equals("!cr_pred") || event.message.startsWith("!cr_pred ")) {
                if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(
                        CommandPermission.BROADCASTER
                    )
                ) {
                    try {
                        if (event.message.equals("!cr_pred ") || event.message.equals("!cr_pred")) {
                            event.reply(
                                twitchClient.chat,
                                "Создать ставку на 5 минут !cr_pred {Название ставки} [Исход 1, Исход 2] 300"
                            )
                        } else {
                            val namePrediction =
                                event.message.substring(event.message.indexOf('{') + 1, event.message.indexOf('}'))
                            val namesOutcomes: List<String> =
                                event.message.substring(event.message.indexOf('[') + 1, event.message.indexOf(']'))
                                    .split(',')
                            val seconds = event.message.split(' ').last().toInt()
                            val outcomes: MutableList<com.github.twitch4j.eventsub.domain.PredictionOutcome> =
                                mutableListOf()
                            for (name in namesOutcomes) {
                                outcomes.add(
                                    com.github.twitch4j.eventsub.domain.PredictionOutcome().withTitle(name.trim())
                                )
                            }
                            helixClient.createPrediction(
                                twitchChannelOAuth2Credential.accessToken, Prediction()
                                    .withBroadcasterId(broadcasterId)
                                    .withPredictionWindowSeconds(seconds)
                                    .withTitle(namePrediction)
                                    .withOutcomes(outcomes)
                            ).execute()
                        }

                    } catch (e: Throwable) {
                        logger.error("Failed cr_pred:", e)
                    }
                }
            }
            if (event.message.equals("!rsl_pred") || event.message.startsWith("!rsl_pred ")) {
                if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(
                        CommandPermission.BROADCASTER
                    )
                ) {
                    try {
                        if (event.message.equals("!rsl_pred ") || event.message.equals("!rsl_pred")) {
                            event.reply(
                                twitchClient.chat,
                                "Закрыть ставку с первым исходом !rsl_pred 1"
                            )
                        } else {
                            val outcomePosition = event.message.split(' ').last().toInt()
                            val predictions = helixClient.getPredictions(
                                twitchChannelOAuth2Credential.accessToken,
                                broadcasterId,
                                null,
                                null,
                                null
                            ).execute()
                            val firstPred = predictions.predictions.get(0)
                            helixClient.endPrediction(
                                twitchChannelOAuth2Credential.accessToken,
                                firstPred.toBuilder()
                                    .status(PredictionStatus.RESOLVED)
                                    .winningOutcomeId(firstPred.outcomes.get(outcomePosition - 1).id)
                                    .build()
                            ).execute()
                        }

                    } catch (e: Throwable) {
                        logger.error("Failed rsl_pred:", e)
                    }
                }
            }
            if (event.message.equals("!fight") || event.message.startsWith("!fight ")) {
                GlobalScope.launch {
                    startDuelCommand(event)
                }
            }

            databaseCommands.firstOrNull { (event.message.equals("!${it.command}") || event.message.startsWith("!${it.command} ")) && it.isEnabled }
                ?.let {
                    try {
                        twitchClient.chat.sendMessage(
                            "c_a_k_e",
                            it.text
                        )
                    } catch (e: Throwable) {
                        logger.error("Failed call ${it.command} command:", e)
                    }
                }
            if (event.message.equals("!gof") || event.message.startsWith("!gof ")) {
                GlobalScope.launch {
                    assignDuelCommand(event)
                }
            }
            if (event.message.startsWith("!sdisable ")) {
                setEnabledCommand(event, false)
            }

            if (event.message.startsWith("!senable ")) {
                setEnabledCommand(event, true)
            }
            if (event.message.equals("!fstats") || event.message.startsWith("!fstats ")) {
                duelStatsCommand(event)
            }
        }
    }

    twitchClient.pubSub.connect()

    val cRewards =
        helixClient.getCustomRewards(twitchChannelOAuth2Credential.accessToken, broadcasterId, null, null).execute()
    for (reward in cRewards.rewards) {
        //        if (reward.title.equals("test") || reward.title.equals("0aa7d314-6ac2-4806-9527-4530f74ebf19")) {
        //            helixClient.deleteCustomReward(
        //                twitchChannelOAuth2Credential.accessToken,
        //                broadcasterId,
        //                reward.id,
        //            ).execute()
        //        }
        if (reward.title.equals(rewardDodoTitle)) {
            rewardDodoID = reward.id
            val customRewards = helixClient.getCustomRewardRedemption(
                twitchChannelOAuth2Credential.accessToken,
                broadcasterId,
                rewardDodoID,
                null,
                RedemptionStatus.UNFULFILLED,
                null,
                null,
                null
            ).execute()
            val redemptionsToCancelIds = customRewards.redemptions.map { it.redemptionId }
            if (redemptionsToCancelIds.isNotEmpty()) {
                helixClient.updateRedemptionStatus(
                    twitchChannelOAuth2Credential.accessToken,
                    broadcasterId, rewardDodoID, redemptionsToCancelIds, RedemptionStatus.CANCELED
                ).execute()
            }
        }
    }
    if (cRewards.rewards.none { it.title.equals(rewardDodoTitle) }) {
        val customReward = CustomReward()
            .withCost(1000000)
            .withTitle(rewardDodoTitle)
            .withPrompt(rewardDodoDescription)
            .withIsUserInputRequired(false)
            .withBackgroundColor("#FF6900")
            .withIsEnabled(true)
            .withMaxPerStreamSetting(
                CustomReward.MaxPerStreamSetting().toBuilder()
                    .isEnabled(true)
                    .maxPerStream(1).build()
            )
            .withMaxPerUserPerStreamSetting(
                CustomReward.MaxPerUserPerStreamSetting().toBuilder()
                    .isEnabled(true)
                    .maxPerUserPerStream(1).build()
            )


        //helixClient.createCustomReward(twitchChannelOAuth2Credential.accessToken, broadcasterId, customReward).execute()
    } else {
        val customReward = CustomReward()
            .withCost(1000000)
            .withTitle(rewardDodoTitle)
            .withPrompt(rewardDodoDescription)
            .withIsEnabled(true)
        /*        helixClient.updateCustomReward(
                    twitchChannelOAuth2Credential.accessToken,
                    broadcasterId,
                    rewardDodoID,
                    customReward
                ).execute()*/
        helixClient.deleteCustomReward(
            twitchChannelOAuth2Credential.accessToken,
            broadcasterId,
            rewardDodoID
        );
    }

    twitchClient.pubSub.listenForChannelPointsRedemptionEvents(twitchChannelOAuth2Credential, broadcasterId)
    twitchClient.pubSub.listenForChannelPredictionsEvents(twitchChannelOAuth2Credential, broadcasterId)
    twitchClient.eventManager.onEvent(RewardRedeemedEvent::class.java, ::onRewardRedeemed)
    twitchClient.eventManager.onEvent(PredictionCreatedEvent::class.java, ::onPredictionCreatedEvent)
    twitchClient.eventManager.onEvent(PredictionUpdatedEvent::class.java, ::onPredictionUpdatedEvent)
}

fun refreshTokensTask() {
    logger.info("refreshTokensTask start")
    val processBuilder = ProcessBuilder()
    processBuilder.command("bash", "-c", "cd /home/bot/twitch_bot/ && . jrestart.sh")
    try {
        try {
            rewardDodoID?.let {
                helixClient.updateCustomReward(
                    twitchChannelOAuth2Credential.accessToken,
                    broadcasterId,
                    rewardDodoID,
                    CustomReward().withIsEnabled(false)
                ).execute()
            }
        } catch (e: Throwable) {
            logger.error("Failed updateCustomReward in refreshTokensTask:", e)
        }
        processBuilder.start()
        logger.info("refreshTokensTask process called")
    } catch (e: Throwable) {
        logger.error("Failed call restart script:", e)
    }
}

fun sendEmail(message: String) {
    logger.info("sendEmail start with message $message")
    val processBuilder = ProcessBuilder()
    var output: String = ""
    val path = System.getProperty("user.dir")
    processBuilder.command("$path/send.sh", "'$message'")
    try {
        val process = processBuilder.start()
        val inputStream = BufferedReader(InputStreamReader(process.getInputStream()))
        while (inputStream.readLine()?.also { output = it } != null) {
            logger.info("sendEmail process output: $output")
        }
        inputStream.close()
        process.waitFor()
        logger.info("sendEmail process called with command ${processBuilder.command().toString()}")
    } catch (e: Throwable) {
        logger.error("Failed call sendEmail: ", e)
    }
}

fun sendTelegram(message: String) {
    logger.info("sendTelegram start with message $message")
    try {
        tgBot.sendMessage(chatId = ChatId.fromId(tgAdminID.toLong()), text = message)
    } catch (e: Throwable) {
        logger.error("Failed sendTelegram: ", e)
    }
}

private fun onRewardRedeemed(rewardRedeemedEvent: RewardRedeemedEvent) {
    try {
        if (rewardRedeemedEvent.redemption.reward.id.equals(rewardDodoID)) {
            val user = rewardRedeemedEvent.redemption.user
            logger.info("onRewardRedeemed, title: ${rewardRedeemedEvent.redemption.reward.title}, user: ${user.displayName}")
            try {
                helixClient.sendWhisper(
                    staregeBotOAuth2Credential.accessToken,
                    moderatorId,
                    user.id,
                    "Ваш промо dodo pizza - $dodoPromo"
                ).execute()
            } catch (e: Throwable) {
                logger.error("Failed sendWhisper: ", e)
                sendTelegram("send promo, failed sendWhisper: $e")
            }
            sendTelegram("send promo $dodoPromo to user: ${user.displayName}")
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                "peepoFat \uD83C\uDF55  @${user.displayName} отправил вам промокод в ЛС :) Если он не пришёл, то возможно у вас заблокирована личка для незнакомых, напишите в личку @olegsvs или в тг @olegsvs"
            )
            helixClient.updateRedemptionStatus(
                twitchChannelOAuth2Credential.accessToken,
                broadcasterId, rewardDodoID, listOf(rewardRedeemedEvent.redemption.id), RedemptionStatus.FULFILLED
            ).execute()
            helixClient.updateCustomReward(
                twitchChannelOAuth2Credential.accessToken,
                broadcasterId,
                rewardDodoID,
                CustomReward().withIsPaused(true)
            ).execute()
        } else if (rewardRedeemedEvent.redemption.reward.id.equals(rewardTestID)) {
            val user = rewardRedeemedEvent.redemption.user
            logger.info("onRewardRedeemed, title: ${rewardRedeemedEvent.redemption.reward.title}, user: ${user.toString()}, userId: ${user.id}")
            helixClient.sendWhisper(
                staregeBotOAuth2Credential.accessToken,
                moderatorId,
                user.id,
                "test"
            ).execute()
        }
    } catch (e: Throwable) {
        logger.error("Failed onRewardRedeemed: ", e)
        sendTelegram("send promo, failed: $e")
        if (rewardRedeemedEvent.redemption.reward.id.equals(rewardDodoID)) {
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                "@${rewardRedeemedEvent.redemption.user.displayName}, ошибка отправки промо додо-пиццы, возможно у вас отключено принятие сообщений в ЛС в настройках приватности, попробуйте позднее Sadge"
            )
            //TODO @(olegsvs): check enabled after cancel
            helixClient.updateRedemptionStatus(
                twitchChannelOAuth2Credential.accessToken,
                broadcasterId, rewardDodoID, listOf(rewardRedeemedEvent.redemption.id), RedemptionStatus.CANCELED
            ).execute()
        }
    }
}

private fun onPredictionCreatedEvent(predictionCreatedEvent: PredictionCreatedEvent) {
    logger.info("onPredictionCreatedEvent")
    try {
        helixClient.sendChatAnnouncement(
            staregeBotOAuth2Credential.accessToken,
            broadcasterId,
            moderatorId,
            "PepegaPhone СТАВКА",
            AnnouncementColor.PURPLE
        ).execute()
    } catch (e: Throwable) {
        logger.error("Failed onPredictionCreatedEvent sendChatAnnouncement: ", e)
    }
}

private fun onPredictionUpdatedEvent(predictionUpdatedEvent: PredictionUpdatedEvent) {
    try {
        if (predictionUpdatedEvent.event.status.equals("RESOLVED")) {
            logger.info("onPrediction RESOLVED Event")
            val win: PredictionOutcome =
                predictionUpdatedEvent.event.outcomes.first { it.id.equals(predictionUpdatedEvent.event.winningOutcomeId) }
            when (win.color.name) {
                "BLUE" -> helixClient.sendChatAnnouncement(
                    staregeBotOAuth2Credential.accessToken,
                    broadcasterId,
                    moderatorId,
                    "BlueWin",
                    AnnouncementColor.PURPLE
                ).execute()

                "PINK" -> helixClient.sendChatAnnouncement(
                    staregeBotOAuth2Credential.accessToken,
                    broadcasterId,
                    moderatorId,
                    "RedWin",
                    AnnouncementColor.PURPLE
                ).execute()
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed onPredictionUpdatedEvent sendChatAnnouncement: ", e)
    }
}

private fun banUsersWithPhrase(event: ChannelMessageEvent, phrase: String) {
    try {
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            logger.info("banUsersWithPhrase request from user ${event.user.name}")
            if (phrase.isEmpty()) {
                event.reply(twitchClient.chat, "DinkDonk Укажите текст фразы для бана")
                return
            }
            val messagesToBan: List<ChannelMessageEvent> = chatMessages.filter { it.message.contains(phrase) }
            logger.info("Filtered messages to ban : ${messagesToBan.toString()}")
            for (message in messagesToBan) {
                logger.error("BanMessage: in loop, message: $message")
                if (message.permissions.contains(CommandPermission.MODERATOR) || message.permissions.contains(
                        CommandPermission.BROADCASTER
                    )
                ) {
                    continue
                }
                if (message.message.contains(phrase)) {
                    try {
                        logger.error("BanUsersWithPhrase: Try ban user: " + message + " " + message.user.id)
                        helixClient.banUser(
                            staregeBotOAuth2Credential.accessToken,
                            broadcasterId,
                            moderatorId,
                            BanUserInput()
                                .withUserId(message.user.id)
                                .withReason("Messages contains banned phrase $phrase")
                        ).execute()
                        logger.info("Banned user ${message.user.name}, reason: phrase $phrase, by user ${event.user.name}")
                    } catch (e: Throwable) {
                        logger.error("BanUsersWithPhrase: Failed ban user: ", e)
                    }
                    try {
                        chatMessages.remove(message)
                    } catch (e: Throwable) {
                        logger.error("Failed chatMessages.remove(message): ", e)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed banUsersWithPhrase: ", e)
    }
}

private fun changeTitle(event: ChannelMessageEvent, newTitle: String) {
    try {
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            logger.info("changeTitle request")
            if (newTitle.isEmpty()) {
                event.reply(twitchClient.chat, "DinkDonk Укажите текст")
                return
            }
            helixClient.updateChannelInformation(
                twitchChannelOAuth2Credential.accessToken,
                broadcasterId,
                ChannelInformation().withTitle(newTitle)
            ).execute()
            event.reply(twitchClient.chat, "Название изменено на $newTitle")
        }
    } catch (e: Throwable) {
        logger.error("Failed changeTitle: ", e)
    }
}

private fun changeCategory(event: ChannelMessageEvent, newCategory: String) {
    try {
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            logger.info("changeCategory request")
            if (newCategory.isEmpty()) {
                event.reply(twitchClient.chat, "DinkDonk Укажите название категории")
                return
            }
            val response =
                helixClient.getGames(staregeBotOAuth2Credential.accessToken, listOf(), listOf(newCategory), listOf())
                    .execute()
            if (response.games.isEmpty()) {
                event.reply(twitchClient.chat, "Категория $newCategory не найдена Sadge")

            } else {
                val game = response.games[0]
                helixClient.updateChannelInformation(
                    twitchChannelOAuth2Credential.accessToken,
                    broadcasterId,
                    ChannelInformation().withGameId(game.id)
                ).execute()
                event.reply(twitchClient.chat, "Категория изменена на ${game.name}")
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed changeCategory: ", e)
    }
}

private fun pingCommand(event: ChannelMessageEvent) {
    logger.info("pingCommand")
    try {
        event.reply(
            twitchClient.chat,
            "Starege pong, доступные команды: ${getEnabledCommands()}. For PETTHEMODS : !title, !sgame - change title/category, !sbanp phrase - ban all users who sent 'phrase' !cr_pred - create prediction, !rsl_pred - resolve prediction"
        )
    } catch (e: Throwable) {
        logger.error("Failed pingCommand: ", e)
    }
}

private fun addTestRewardCommand(event: ChannelMessageEvent) {
    logger.info("addTestRewardCommand")
    if (!event.permissions.contains(CommandPermission.MODERATOR) && !event.permissions.contains(CommandPermission.BROADCASTER)) {
        return
    }
    try {
        val cRewards =
            helixClient.getCustomRewards(twitchChannelOAuth2Credential.accessToken, broadcasterId, null, null).execute()
        if (cRewards.rewards.none { it.title.equals(rewardTestTitle) }) {
            val customReward = CustomReward()
                .withCost(1)
                .withTitle(rewardTestTitle)
            val newReward =
                helixClient.createCustomReward(twitchChannelOAuth2Credential.accessToken, broadcasterId, customReward)
                    .execute()
            val reward = newReward.rewards.find { it.title.equals(rewardTestTitle) }
            if (reward != null) {
                rewardTestID = reward.id
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed addTestRewardCommand: ", e)
    }
}

private fun deleteTestRewardCommand(event: ChannelMessageEvent) {
    logger.info("deleteTestRewardCommand")
    if (!event.permissions.contains(CommandPermission.MODERATOR) && !event.permissions.contains(CommandPermission.BROADCASTER)) {
        return
    }
    try {
        val cRewards =
            helixClient.getCustomRewards(twitchChannelOAuth2Credential.accessToken, broadcasterId, null, null).execute()
        for (reward in cRewards.rewards) {
            if (reward.title.equals(rewardTestTitle)) {
                helixClient.deleteCustomReward(
                    twitchChannelOAuth2Credential.accessToken,
                    broadcasterId,
                    reward.id,
                ).execute()
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed deleteTestRewardCommand: ", e)
    }
}

private fun getEnabledCommands(): String {
    return "${commands.commands.filter { it.enabled }.map { "!${it.name} " }}".removePrefix("[").removeSuffix(" ]")
}

private fun setEnabledCommand(event: ChannelMessageEvent, enabled: Boolean) {
    logger.info("setEnabledCommand")
    try {
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            val commandName = event.message.split(" ")[1]
            val result = commands.setEnabled(commandName, enabled)
            if (result) {
                commands.save()
                val hint = if (enabled) "включена" else "отключена"
                event.reply(
                    twitchClient.chat,
                    "Starege : команда $commandName $hint"
                )
            }
        }
    } catch (e: Throwable) {
        logger.error("Failed setEnabledCommand: ", e)
    }
}

var lastYoutubeCommand: Long? = null
private suspend fun getLastYoutubeHighlight(event: ChannelMessageEvent) {
    if (!commands.isEnabled("yt")) return
    logger.info("getLastYoutubeHighlight")
    try {
        if (lastYoutubeCommand != null) {
            val diff = System.currentTimeMillis() - lastYoutubeCommand!!
            if (diff < 3000) return
        }
        lastYoutubeCommand = System.currentTimeMillis()
        val playlists: YoutubeChannelResponse =
            httpClient.request("https://www.googleapis.com/youtube/v3/channels?id=${youtubeChannelKey}&key=${youtubeApiKey}&part=contentDetails")
                .body()
        val playListID = playlists.items[0].contentDetails.relatedPlaylists.uploads
        logger.info("youtube playListID: $playListID")
        val videosInPlayList: YoutubeVideosResponse =
            httpClient.request("https://www.googleapis.com/youtube/v3/playlistItems?playlistId=${playListID}&key=${youtubeApiKey}&part=snippet&maxResults=20")
                .body()
        var found: VideoItem? = null
        for (video in videosInPlayList.items) {
//            logger.info(video.snippet.description)
//            logger.info(video.snippet.title)
            if (video.snippet.description.contains("shorts") or video.snippet.title.contains("@CakeStream"))
                continue
            found = video
            break
        }
        if (found != null) {
            val title = found.snippet.title
            val videoUrl = "https://www.youtube.com/watch?v=${found.snippet.resourceId.videoId}"
            val finalText =
                "Подписывайся на ютуб - https://goo.su/W5UDBz ! Ежедневно новые шортсы и очень часто новые хайлайты. Последний хайлайт - $title $videoUrl"
            event.reply(twitchClient.chat, finalText)
        } else {
            logger.info("getLastYoutubeHighlight: video not found")
            event.reply(
                twitchClient.chat,
                "Подписывайся на ютуб - https://goo.su/W5UDBz ! Ежедневно новые шортсы и очень часто новые хайлайты."
            )
        }

    } catch (e: Throwable) {
        logger.error("Failed getLastYoutubeHighlight: ", e)
        event.reply(
            twitchClient.chat,
            "Подписывайся на ютуб - https://goo.su/W5UDBz ! Ежедневно новые шортсы и очень часто новые хайлайты."
        )
    }
}

var duelIsStarted = false
var duelFirstUserMessage: ChannelMessageEvent? = null
var duelSecondUserMessage: ChannelMessageEvent? = null
var lastDuel: Long? = System.currentTimeMillis() - 300000 // minus 5 min default
private suspend fun startDuelCommand(event: ChannelMessageEvent) {
    val channelName = event.channel.name;
    if (!commands.isEnabled("fight")) {
//        event.reply(
//            twitchClient.chat,
//            "Используйте команду !зaряд"
//        )
        return
    }
    logger.info("duel request")
    try {
        /*if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }*/

        if (duelIsStarted) {
            assignDuelCommand(event)
            logger.info("Duel: another duel already started")
            return
        }

        val now = System.currentTimeMillis() / 1000
        if (lastDuel != null) {
            val diff = (now - lastDuel!! / 1000)
            if (diff < 300) {
                val nextRollTime = (300 - diff)
                val nextRollMinutes = (nextRollTime % 3600) / 60
                val nextRollSeconds = (nextRollTime % 3600) % 60
                event.reply(
                    twitchClient.chat,
                    "Ринг отмывают, осталось \uD83D\uDD5B ${nextRollMinutes}m${nextRollSeconds}s Modge"
                )
                logger.info("Duel: diff")
                return
            }
        }
        duelIsStarted = true
        duelFirstUserMessage = event
        /*        event.reply(
                    twitchClient.chat,
                    "@${event.user.name} ищет смельчака на бой! Проигравшему чаттерсу без модерки мут на 10 минут(а модеру -rep). Пиши !gof за минуту, чтобы подраться snejok"
                )*/
        twitchClient.chat.sendMessage(
            "c_a_k_e",
            "@${event.user.name} ищет смельчака на бой! Проигравшему без модерки - мут на 10 минут(а модеру -rep). Пиши !gof за минуту, чтобы подраться snejok"
        )
        logger.info("Duel: wait another user")
        delay(Duration.ofMinutes(1).toMillis())
        if (duelFirstUserMessage != null || duelSecondUserMessage != null) {
            logger.info("Duel: clean duel 1")
            duelFirstUserMessage = null
            duelSecondUserMessage = null
            duelIsStarted = false
        }
    } catch (e: Throwable) {
        logger.info("Duel: clean duel 2")
        duelFirstUserMessage = null
        duelSecondUserMessage = null
        duelIsStarted = false
        logger.error("Failed fightCommand: ", e)
    }
}

private suspend fun assignDuelCommand(event: ChannelMessageEvent) {
    logger.info("duel assign request")
    try {
        /*if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }*/
        if (!duelIsStarted) {
            return
        }

        if (duelFirstUserMessage == null) {
            duelFirstUserMessage = null
            duelSecondUserMessage = null
            duelIsStarted = false
            return
        }

        if (!duelFirstUserMessage!!.user.id.equals(event.user.id)) {
            duelSecondUserMessage = event
        } else {
            return
        }
        val now = System.currentTimeMillis()
        lastDuel = now
        duelIsStarted = false

        /*
            await chat_bot.send_message(TARGET_CHANNEL, 'Opachki начинается дуэль между ' + duel_first_user_message.user.name + ' и ' + duel_second_user_message.user.name)
            await asyncio.sleep(5)
         */

        /*event.reply(
            twitchClient.chat,
            "Opachki начинается дуэль между ${duelFirstUserMessage!!.user.name} и ${duelSecondUserMessage!!.user.name}"
        )*/
        twitchClient.chat.sendMessage(
            "c_a_k_e",
            "Opachki начинается дуэль между ${duelFirstUserMessage!!.user.name} и ${duelSecondUserMessage!!.user.name}"
        )
        delay(5000)
        val rnd = (0..1).random()
        val rndDraw = (0..99).random()
        if (rndDraw == 0) {
            val firstUser: User =
                users.getByIdOrCreate(duelFirstUserMessage!!.user.id, duelFirstUserMessage!!.user.name)
            users.update(firstUser.win(5))
            val secondUser: User =
                users.getByIdOrCreate(duelSecondUserMessage!!.user.id, duelSecondUserMessage!!.user.name)
            users.update(secondUser.win(5))
            /*event.reply(
                twitchClient.chat,
                " PogT SHTO ничья, всем +1 очко YEP"
            )*/
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                " PogT SHTO ничья, всем +5 очков YEP"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessage = null
            duelSecondUserMessage = null
            duelIsStarted = false
            return
        }
        val winner: ChannelMessageEvent?
        val loser: ChannelMessageEvent?
        if (rnd == 0) {
            winner = duelFirstUserMessage!!
            loser = duelSecondUserMessage!!
        } else {
            winner = duelSecondUserMessage!!
            loser = duelFirstUserMessage!!
        }
        val winnerUser: User = users.getByIdOrCreate(winner.user.id, winner.user.name)
        users.update(winnerUser.win())
        val loserUser: User = users.getByIdOrCreate(loser.user.id, loser.user.name)
        users.update(loserUser.lose())
        if (loser.permissions.contains(CommandPermission.MODERATOR) || loser.permissions.contains(CommandPermission.BROADCASTER)) {
            /*event.reply(
                twitchClient.chat,
                "${winner.user.name} ${winnerUser.stats} EZ победил модератора Jokerge forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его модерировать чат NuAHule"
            )*/
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                "${winner.user.name} ${winnerUser.stats} EZ победил модера Jokerge forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его чистить чат Modge"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessage = null
            duelSecondUserMessage = null
            duelIsStarted = false
        } else {
            /*event.reply(
                twitchClient.chat,
                "${winner.user.name} ${winnerUser.stats} EZ победил forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его отдыхать на 10 минут SadgeCry Timeout"
            )*/
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                "${winner.user.name} ${winnerUser.stats} EZ победил forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его отдыхать на 10 минут SadgeCry Timeout"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessage = null
            duelSecondUserMessage = null
            duelIsStarted = false
            delay(5000)
            helixClient.banUser(
                staregeBotOAuth2Credential.accessToken,
                broadcasterId,
                moderatorId,
                BanUserInput()
                    .withUserId(loser.user.id)
                    .withDuration(600)
                    .withReason("duel with ${winner.user.name}")
            ).execute()
        }
    } catch (e: Throwable) {
        logger.info("Duel: clean duel 7")
        duelFirstUserMessage = null
        duelSecondUserMessage = null
        duelIsStarted = false
        logger.error("Failed assignDuel: ", e)
    }
}

var lastDuelStatsCommand: Long? = null
private fun duelStatsCommand(event: ChannelMessageEvent) {
    if (!commands.isEnabled("fstats")) return
    logger.info("duelStatsCommand")
    try {
        if (lastDuelStatsCommand != null) {
            val diff = System.currentTimeMillis() - lastDuelStatsCommand!!
            if (diff < 60000) return
        }
        lastDuelStatsCommand = System.currentTimeMillis()
        event.reply(
            twitchClient.chat,
            "Top 1 wins EZ : ${users.getMaxWinsUser().userName} ${users.getMaxWinsUser().stats}, " +
                    "Top 1 loses Jokerge : ${users.getMaxLosesUser().userName} ${users.getMaxLosesUser().stats}"
        )
    } catch (e: Throwable) {
        logger.error("Failed duelStatsCommand: ", e)
    }
}


//lady_rocket_cat
var duelIsStartedLR = false
var duelFirstUserMessageLR: ChannelMessageEvent? = null
var duelSecondUserMessageLR: ChannelMessageEvent? = null
var lastDuelLR: Long? = System.currentTimeMillis() - 300000 // minus 5 min default
private suspend fun startDuelCommandLR(event: ChannelMessageEvent) {
    logger.info("duel request")
    try {
        /*if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }*/

        if (duelIsStartedLR) {
            assignDuelCommand(event)
            logger.info("Duel: another duel already started")
            return
        }

        val now = System.currentTimeMillis() / 1000
        if (lastDuelLR != null) {
            val diff = (now - lastDuelLR!! / 1000)
            if (diff < 300) {
                val nextRollTime = (300 - diff)
                val nextRollMinutes = (nextRollTime % 3600) / 60
                val nextRollSeconds = (nextRollTime % 3600) % 60
                event.reply(
                    twitchClient.chat,
                    "Ринг отмывают, осталось \uD83D\uDD5B ${nextRollMinutes}m${nextRollSeconds}s Modge"
                )
                logger.info("Duel: diff")
                return
            }
        }
        duelIsStartedLR = true
        duelFirstUserMessageLR = event
        /*        event.reply(
                    twitchClient.chat,
                    "@${event.user.name} ищет смельчака на бой! Проигравшему чаттерсу без модерки мут на 10 минут(а модеру -rep). Пиши !gof за минуту, чтобы подраться snejok"
                )*/
        twitchClient.chat.sendMessage(
            "lady_rockycat",
            "@${event.user.name} ищет смельчака на бой! Проигравшему без модерки - мут на 10 минут(а модеру -rep). Пиши !gof за минуту, чтобы подраться snejok"
        )
        logger.info("Duel: wait another user")
        delay(Duration.ofMinutes(1).toMillis())
        if (duelFirstUserMessageLR != null || duelSecondUserMessageLR != null) {
            logger.info("Duel: clean duel 1")
            duelFirstUserMessageLR = null
            duelSecondUserMessageLR = null
            duelIsStartedLR = false
        }
    } catch (e: Throwable) {
        logger.info("Duel: clean duel 2")
        duelFirstUserMessageLR = null
        duelSecondUserMessageLR = null
        duelIsStartedLR = false
        logger.error("Failed fightCommand: ", e)
    }
}

private suspend fun assignDuelCommandLR(event: ChannelMessageEvent) {
    logger.info("duel assign request")
    try {
        /*if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }*/
        if (!duelIsStartedLR) {
            return
        }

        if (duelFirstUserMessageLR == null) {
            duelFirstUserMessageLR = null
            duelSecondUserMessageLR = null
            duelIsStartedLR = false
            return
        }

        if (!duelFirstUserMessageLR!!.user.id.equals(event.user.id)) {
            duelSecondUserMessageLR = event
        } else {
            return
        }
        val now = System.currentTimeMillis()
        lastDuelLR = now
        duelIsStartedLR = false

        /*
            await chat_bot.send_message(TARGET_CHANNEL, 'Opachki начинается дуэль между ' + duel_first_user_message.user.name + ' и ' + duel_second_user_message.user.name)
            await asyncio.sleep(5)
         */

        /*event.reply(
            twitchClient.chat,
            "Opachki начинается дуэль между ${duelFirstUserMessage!!.user.name} и ${duelSecondUserMessage!!.user.name}"
        )*/
        twitchClient.chat.sendMessage(
            "lady_rockycat",
            "Opachki начинается дуэль между ${duelFirstUserMessageLR!!.user.name} и ${duelSecondUserMessageLR!!.user.name}"
        )
        delay(5000)
        val rnd = (0..1).random()
        val rndDraw = (0..99).random()
        if (rndDraw == 0) {
            val firstUser: User =
                users.getByIdOrCreate(duelFirstUserMessageLR!!.user.id, duelFirstUserMessageLR!!.user.name)
            users.update(firstUser.win(5))
            val secondUser: User =
                users.getByIdOrCreate(duelSecondUserMessageLR!!.user.id, duelSecondUserMessageLR!!.user.name)
            users.update(secondUser.win(5))
            /*event.reply(
                twitchClient.chat,
                " PogT SHTO ничья, всем +1 очко YEP"
            )*/
            twitchClient.chat.sendMessage(
                "lady_rockycat",
                " PogT SHTO ничья, всем +5 очков YEP"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessageLR = null
            duelSecondUserMessageLR = null
            duelIsStartedLR = false
            return
        }
        val winner: ChannelMessageEvent?
        val loser: ChannelMessageEvent?
        if (rnd == 0) {
            winner = duelFirstUserMessageLR!!
            loser = duelSecondUserMessageLR!!
        } else {
            winner = duelSecondUserMessageLR!!
            loser = duelFirstUserMessageLR!!
        }
        val winnerUser: User = users.getByIdOrCreate(winner.user.id, winner.user.name)
        users.update(winnerUser.win())
        val loserUser: User = users.getByIdOrCreate(loser.user.id, loser.user.name)
        users.update(loserUser.lose())
        if (loser.permissions.contains(CommandPermission.MODERATOR) || loser.permissions.contains(CommandPermission.BROADCASTER)) {
            /*event.reply(
                twitchClient.chat,
                "${winner.user.name} ${winnerUser.stats} EZ победил модератора Jokerge forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его модерировать чат NuAHule"
            )*/
            twitchClient.chat.sendMessage(
                "lady_rockycat",
                "${winner.user.name} ${winnerUser.stats} EZ победил модера Jokerge forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его чистить чат Modge"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessageLR = null
            duelSecondUserMessageLR = null
            duelIsStartedLR = false
        } else {
            /*event.reply(
                twitchClient.chat,
                "${winner.user.name} ${winnerUser.stats} EZ победил forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его отдыхать на 10 минут SadgeCry Timeout"
            )*/
            twitchClient.chat.sendMessage(
                "lady_rockycat",
                "${winner.user.name} ${winnerUser.stats} EZ победил forsenLaughingAtYou ${loser.user.name} ${loserUser.stats} и отправил его отдыхать на 10 минут SadgeCry Timeout"
            )
            logger.info("Duel: clean duel 6")
            duelFirstUserMessageLR = null
            duelSecondUserMessageLR = null
            duelIsStartedLR = false
            delay(5000)
            helixClient.banUser(
                staregeBotOAuth2Credential.accessToken,
                broadcasterIdLR,
                moderatorId,
                BanUserInput()
                    .withUserId(loser.user.id)
                    .withDuration(600)
                    .withReason("duel with ${winner.user.name}")
            ).execute()
        }
    } catch (e: Throwable) {
        logger.info("Duel: clean duel 7")
        duelFirstUserMessageLR = null
        duelSecondUserMessageLR = null
        duelIsStartedLR = false
        logger.error("Failed assignDuel: ", e)
    }
}

var lastDuelStatsCommandLR: Long? = null
private fun duelStatsCommandLR(event: ChannelMessageEvent) {
    logger.info("duelStatsCommand")
    try {
        if (lastDuelStatsCommandLR != null) {
            val diff = System.currentTimeMillis() - lastDuelStatsCommandLR!!
            if (diff < 60000) return
        }
        lastDuelStatsCommandLR = System.currentTimeMillis()
        event.reply(
            twitchClient.chat,
            "Top 1 wins EZ : ${users.getMaxWinsUser().userName} ${users.getMaxWinsUser().stats}, " +
                    "Top 1 loses Jokerge : ${users.getMaxLosesUser().userName} ${users.getMaxLosesUser().stats}"
        )
    } catch (e: Throwable) {
        logger.error("Failed duelStatsCommand: ", e)
    }
}