import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.philippheuer.credentialmanager.domain.OAuth2Credential
import com.github.philippheuer.events4j.simple.SimpleEventHandler
import com.github.twitch4j.TwitchClient
import com.github.twitch4j.TwitchClientBuilder
import com.github.twitch4j.chat.events.channel.ChannelMessageEvent
import com.github.twitch4j.common.enums.CommandPermission
import com.github.twitch4j.eventsub.domain.RedemptionStatus
import com.github.twitch4j.helix.TwitchHelix
import com.github.twitch4j.helix.TwitchHelixBuilder
import com.github.twitch4j.helix.domain.AnnouncementColor
import com.github.twitch4j.helix.domain.BanUserInput
import com.github.twitch4j.helix.domain.ChannelInformation
import com.github.twitch4j.helix.domain.CustomReward
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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.Duration
import java.util.*

val logger: Logger = LoggerFactory.getLogger("bot")
val dotenv = Dotenv.load()

//  TODO(@olegsvs): fix chars, WTF
val dodoPromo = dotenv.get("DODO_PROMO").replace("'", "")
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
val commands = gsonPretty.fromJson(File("commands.json").readText(), Commands::class.java) as Commands

val staregeBotOAuth2Credential = OAuth2Credential("twitch", staregeBotAccessToken)
val twitchChannelOAuth2Credential = OAuth2Credential("twitch", twitchChannelAccessToken)

val youtubeApiKey = dotenv.get("YOUTUBE_API_KEY").replace("'", "")
val youtubeChannelKey = dotenv.get("YOUTUBE_CHANNEL_KEY").replace("'", "")
val youtubeCommandTriggers = listOf("!yt", "!ютуб", "!youtube")

val broadcasterId = dotenv.get("BROADCASTER_ID").replace("'", "")
val moderatorId = dotenv.get("MODERATOR_ID").replace("'", "")

val twitchClientId = dotenv.get("TWITCH_CLIENT_ID").replace("'", "")
val twitchClientSecret = dotenv.get("TWITCH_CLIENT_SECRET").replace("'", "")

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
val chatMessages: MutableList<ChannelMessageEvent> = mutableListOf()

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    logger.info("Bot started")
    Timer().scheduleAtFixedRate(object : TimerTask() {
        override fun run() {
            refreshTokensTask()
        }
    }, testDefaultRefreshRateTokensTimeMillis, testDefaultRefreshRateTokensTimeMillis)
    twitchClient.chat.joinChannel("c_a_k_e")
    twitchClient.eventManager.onEvent(ChannelMessageEvent::class.java) { event ->
        chatMessages.add(event)
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
        if (event.message.equals("!stestemail") || event.message.startsWith("!stestemail ")) {
            sendEmail("send promo $rewardDodoTitle to user: ${event.user.name}")
        }
        if (event.message.equals("!fight") || event.message.startsWith("!fight ")) {
            GlobalScope.launch {
                startDuelCommand(event)
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
        if (event.message.equals("!saddtestreward") || event.message.startsWith("!saddtestreward ")) {
            addTestRewardCommand(event)
        }
        if (event.message.equals("!srmtestreward") || event.message.startsWith("!srmtestreward ")) {
            deleteTestRewardCommand(event)
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
            .withCost(1500000)
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
        helixClient.createCustomReward(twitchChannelOAuth2Credential.accessToken, broadcasterId, customReward).execute()
    } else {
        val customReward = CustomReward()
            .withCost(1500000)
            .withTitle(rewardDodoTitle)
            .withPrompt(rewardDodoDescription)
            .withIsEnabled(true)
        helixClient.updateCustomReward(
            twitchChannelOAuth2Credential.accessToken,
            broadcasterId,
            rewardDodoID,
            customReward
        ).execute()
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

private fun onRewardRedeemed(rewardRedeemedEvent: RewardRedeemedEvent) {
    try {
        if (rewardRedeemedEvent.redemption.reward.id.equals(rewardDodoID)) {
            logger.info("onRewardRedeemed, title: ${rewardRedeemedEvent.redemption.reward.title}")
            val user = rewardRedeemedEvent.redemption.user
            helixClient.sendWhisper(
                staregeBotOAuth2Credential.accessToken,
                moderatorId,
                user.id,
                "Ваш промо dodo pizza - $dodoPromo"
            ).execute()
            sendEmail("send promo $dodoPromo to user: ${user.displayName}")
            twitchClient.chat.sendMessage(
                "c_a_k_e",
                "peepoFat \uD83C\uDF55  @${user.displayName} отправил вам промокод в ЛС :) Если он не пришёл, то возможно у вас заблокирована личка для незнакомых, напишите в личку @Sentry__Ward или в тг @olegsvs"
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
            val messagesToBan : List<ChannelMessageEvent> = chatMessages.filter { it.message.contains(phrase) }
            for (message in messagesToBan) {
                if (message.permissions.contains(CommandPermission.MODERATOR) || message.permissions.contains(CommandPermission.BROADCASTER)) {
                    continue
                }
                if(message.message.contains(phrase)) {
                    try {
                        helixClient.banUser(
                            staregeBotOAuth2Credential.accessToken,
                            broadcasterId,
                            moderatorId,
                            BanUserInput()
                                .withUserId(message.user.id)
                                .withReason("Messages contains banned phrase $phrase")
                        ).execute()
                    } catch (e: Throwable) {
                        logger.error("BanUsersWithPhrase: Failed ban user: ", e)
                    }
                    logger.info("Banned user ${message.user.name}, reason: phrase $phrase, by user ${event.user.name}")
                    chatMessages.remove(message)
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
    if (!commands.isEnabled("sping")) return
    logger.info("pingCommand")
    try {
        event.reply(
            twitchClient.chat,
            "Starege pong, доступные команды: ${getEnabledCommands()}. For PETTHEMODS : !title, !sgame - change title/category, !sbanp phrase - ban all users who sent 'phrase'"
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
                File("commands.json").writeText(gsonPretty.toJson(commands))
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
            if (video.snippet.description.contains("shorts") or video.snippet.title.contains("@CakeStream"))
                continue
            found = video
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
var lastDuel: Long? = System.currentTimeMillis()
private suspend fun startDuelCommand(event: ChannelMessageEvent) {
    if (!commands.isEnabled("fight")) return
    logger.info("duel request")
    try {
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }

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
        event.reply(
            twitchClient.chat,
            "@${event.user.name} ищет смельчака на бой, проигравшему - мут на 10 минут, напиши !gof, чтобы принять вызов(ожидание - 1 минута)"
        )
        logger.info("Duel: wait another user")
        delay(Duration.ofMinutes(1).toMillis())
        logger.info("Duel: clean duel 1")
        duelFirstUserMessage = null
        duelSecondUserMessage = null
        duelIsStarted = false
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
        if (event.permissions.contains(CommandPermission.MODERATOR) || event.permissions.contains(CommandPermission.BROADCASTER)) {
            event.reply(
                twitchClient.chat,
                "Вы неуязвимы для боя EZ"
            )
            return
        }
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

        event.reply(
            twitchClient.chat,
            "Opachki начинается дуэль между ${duelFirstUserMessage!!.user.name} и ${duelSecondUserMessage!!.user.name}"
        )
        delay(5000)
        val rnd = (0..1).random()
        val winner: ChannelMessageEvent?
        val looser: ChannelMessageEvent?
        if (rnd == 0) {
            winner = duelFirstUserMessage!!
            looser = duelSecondUserMessage!!
        } else {
            winner = duelSecondUserMessage!!
            looser = duelFirstUserMessage!!
        }
        event.reply(
            twitchClient.chat,
            "${winner.user.name}  EZ победил в поединке против forsenLaughingAtYou ${looser.user.name} и отправил его отдыхать на 10 минут SadgeCry"
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
                .withUserId(looser.user.id)
                .withDuration(600)
                .withReason("duel with ${winner.user.name}")
        ).execute()
    } catch (e: Throwable) {
        logger.info("Duel: clean duel 7")
        duelFirstUserMessage = null
        duelSecondUserMessage = null
        duelIsStarted = false
        logger.error("Failed assignDuel: ", e)
    }
}

private fun askGPT() {
    // TODO
}