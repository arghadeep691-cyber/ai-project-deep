package com.example.data

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ChatRepository(private val chatDao: ChatDao) {

    private fun generateRandomCode(): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { allowedChars.random() }.joinToString("")
    }

    fun isRoomExpired(room: ChatRoom): Boolean {
        val ageMs = System.currentTimeMillis() - room.createdAt
        return when (room.expiryType) {
            "1h" -> ageMs > 3600_000
            "24h" -> ageMs > 86400_000
            "7d" -> ageMs > 7 * 86400_000
            else -> false
        }
    }

    fun getAllRooms(): Flow<List<ChatRoom>> = chatDao.getAllRooms()
    fun getRoomFlow(roomId: String): Flow<ChatRoom?> = chatDao.getRoomFlow(roomId)
    suspend fun getRoom(roomId: String): ChatRoom? = chatDao.getRoom(roomId)
    fun getParticipantsFlow(roomId: String): Flow<List<Participant>> = chatDao.getParticipantsFlow(roomId)
    fun getGuestFlow(roomId: String): Flow<Participant?> = chatDao.getGuestFlow(roomId)
    fun getMessagesFlow(roomId: String): Flow<List<ChatMessage>> = chatDao.getMessagesFlow(roomId)
    fun getActivityLogsFlow(roomId: String): Flow<List<ActivityLog>> = chatDao.getActivityLogsFlow(roomId)

    suspend fun createRoom(expiryType: String): ChatRoom = withContext(Dispatchers.IO) {
        val roomId = UUID.randomUUID().toString()
        val code = generateRandomCode()
        val inviteLink = "https://ais-dev-b524shvwaqjmwcuv2gsvjs-885510040913.asia-east1.run.app/room/$roomId"
        val room = ChatRoom(
            id = roomId,
            inviteLink = inviteLink,
            secretCode = code,
            expiryType = expiryType,
            createdAt = System.currentTimeMillis()
        )
        chatDao.insertRoom(room)

        val admin = Participant(
            id = "${roomId}_admin",
            roomId = roomId,
            name = "Creator Admin (Me)",
            role = "admin",
            isApproved = true,
            isOnline = true
        )
        chatDao.insertParticipant(admin)
        chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Room created with code $code (exp: $expiryType)."))
        room
    }

    suspend fun requestJoin(name: String, secretCode: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val uppercaseCode = secretCode.uppercase().trim()
        val room = chatDao.getRoomBySecretCode(uppercaseCode)
            ?: return@withContext Pair(false, "Invalid secret code. Please verify.")

        if (room.status == "ended") return@withContext Pair(false, "This chat room session has ended.")
        if (room.isLocked) return@withContext Pair(false, "This chat room is locked.")
        if (isRoomExpired(room)) return@withContext Pair(false, "This room has expired.")

        val participants = chatDao.getParticipants(room.id)
        val approvedGuest = participants.firstOrNull { it.role == "guest" && it.isApproved }
        if (approvedGuest != null && approvedGuest.name != name) {
            return@withContext Pair(false, "Only two participants are allowed.")
        }

        val guestId = "${room.id}_guest"
        val guest = Participant(
            id = guestId,
            roomId = room.id,
            name = name,
            role = "guest",
            isApproved = false,
            isRequestPending = true,
            isOnline = true
        )
        chatDao.insertParticipant(guest)
        chatDao.insertActivityLog(ActivityLog(roomId = room.id, action = "$name requested access using code $uppercaseCode."))
        Pair(true, room.id)
    }

    suspend fun approveGuest(roomId: String) = withContext(Dispatchers.IO) {
        val participants = chatDao.getParticipants(roomId)
        val guest = participants.firstOrNull { it.role == "guest" }
        if (guest != null) {
            chatDao.insertParticipant(guest.copy(isApproved = true, isRequestPending = false))
            chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Approved guest entry for ${guest.name}."))
            adminSystemMessage(roomId, "Welcome to the room! This is a secure private chat. Receipts and typing statuses are live.")
        }
    }

    suspend fun removeGuest(roomId: String) = withContext(Dispatchers.IO) {
        val participants = chatDao.getParticipants(roomId)
        val guest = participants.firstOrNull { it.role == "guest" }
        if (guest != null) {
            chatDao.deleteParticipant(guest.id)
            chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Removed guest ${guest.name}."))
            adminSystemMessage(roomId, "The guest has been removed by the Admin.")
        }
    }

    suspend fun setRoomLocked(roomId: String, locked: Boolean) = withContext(Dispatchers.IO) {
        val room = chatDao.getRoom(roomId)
        if (room != null) {
            chatDao.insertRoom(room.copy(isLocked = locked))
            chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Room is now ${if (locked) "LOCKED" else "UNLOCKED"}."))
        }
    }

    suspend fun rotateCredentials(roomId: String) = withContext(Dispatchers.IO) {
        val room = chatDao.getRoom(roomId)
        if (room != null) {
            val newCode = generateRandomCode()
            chatDao.insertRoom(room.copy(secretCode = newCode))
            chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Rotated credentials. Code: $newCode"))
        }
    }

    suspend fun endSession(roomId: String) = withContext(Dispatchers.IO) {
        val room = chatDao.getRoom(roomId)
        if (room != null) {
            chatDao.insertRoom(room.copy(status = "ended"))
            chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Session ended by Admin."))
        }
    }

    suspend fun updateStatus(roomId: String, role: String, isOnline: Boolean, isTyping: Boolean = false) = withContext(Dispatchers.IO) {
        val participantId = "${roomId}_$role"
        val existing = chatDao.getParticipant(participantId)
        if (existing != null) {
            chatDao.insertParticipant(existing.copy(isOnline = isOnline, lastSeenAt = System.currentTimeMillis(), isTyping = isTyping))
            if (isOnline) {
                val otherRole = if (role == "admin") "guest" else "admin"
                val unread = chatDao.getMessages(roomId).filter { it.senderRole == otherRole && it.deliveryStatus != "read" }
                for (msg in unread) {
                    chatDao.updateMessage(msg.copy(deliveryStatus = "read"))
                }
            }
        }
    }

    suspend fun deleteConversation(roomId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForRoom(roomId)
        chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "Conversation wiped permanently."))
        val notice = ChatMessage(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            senderId = "system",
            senderRole = "admin",
            content = "This conversation was deleted by the admin.",
            messageType = "text",
            timestamp = System.currentTimeMillis(),
            deliveryStatus = "read"
        )
        chatDao.insertMessage(notice)
    }

    private suspend fun adminSystemMessage(roomId: String, content: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            senderId = "system",
            senderRole = "admin",
            content = content,
            messageType = "text",
            timestamp = System.currentTimeMillis(),
            deliveryStatus = "read"
        )
        chatDao.insertMessage(message)
    }

    suspend fun sendMessage(roomId: String, senderRole: String, content: String, messageType: String = "text", replyToId: String? = null): ChatMessage = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        var replyText: String? = null
        var replySender: String? = null
        if (replyToId != null) {
            chatDao.getMessage(replyToId)?.let {
                replyText = if (it.messageType == "text") it.content else "[${it.messageType}]"
                replySender = if (it.senderRole == "admin") "Admin" else "Guest"
            }
        }

        val otherRole = if (senderRole == "admin") "guest" else "admin"
        val other = chatDao.getParticipant("${roomId}_$otherRole")
        val status = if (other?.isOnline == true) "read" else "sent"

        val message = ChatMessage(
            id = id,
            roomId = roomId,
            senderId = "${roomId}_$senderRole",
            senderRole = senderRole,
            content = content,
            messageType = messageType,
            timestamp = System.currentTimeMillis(),
            replyToId = replyToId,
            replyToText = replyText,
            replyToSender = replySender,
            deliveryStatus = status
        )
        chatDao.insertMessage(message)
        chatDao.insertActivityLog(ActivityLog(roomId = roomId, action = "${if (senderRole == "admin") "Admin" else "Guest"} sent $messageType message."))
        message
    }

    suspend fun toggleReaction(roomId: String, messageId: String, role: String, emoji: String) = withContext(Dispatchers.IO) {
        val msg = chatDao.getMessage(messageId) ?: return@withContext
        val currentReactions = parseReactions(msg.reactions).toMutableMap()
        val activeUsers = currentReactions[emoji]?.toMutableList() ?: mutableListOf()

        if (activeUsers.contains(role)) {
            activeUsers.remove(role)
        } else {
            activeUsers.add(role)
        }

        if (activeUsers.isEmpty()) {
            currentReactions.remove(emoji)
        } else {
            currentReactions[emoji] = activeUsers
        }

        chatDao.updateMessage(msg.copy(reactions = serializeReactions(currentReactions)))
    }

    suspend fun togglePinMessage(messageId: String) = withContext(Dispatchers.IO) {
        val msg = chatDao.getMessage(messageId) ?: return@withContext
        chatDao.updateMessage(msg.copy(isPinned = !msg.isPinned))
    }

    fun parseReactions(s: String): Map<String, List<String>> {
        if (s.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, List<String>>()
        try {
            val blocks = s.split("|")
            for (block in blocks) {
                if (block.contains(":")) {
                    val parts = block.split(":")
                    val emoji = parts[0]
                    val roles = parts[1].split(",")
                    result[emoji] = roles.filter { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error parsing reactions: $s", e)
        }
        return result
    }

    private fun serializeReactions(map: Map<String, List<String>>): String {
        return map.entries.joinToString("|") { "${it.key}:${it.value.joinToString(",")}" }
    }

    fun triggerSimulatedCompanionResponse(roomId: String, incomingSenderRole: String, messageText: String, scope: CoroutineScope) {
        val companionRole = if (incomingSenderRole == "admin") "guest" else "admin"
        scope.launch {
            updateStatus(roomId, companionRole, isOnline = true, isTyping = false)
            delay(1200)
            updateStatus(roomId, companionRole, isOnline = true, isTyping = true)

            val prompt = "Reply to: \"$messageText\" in a brief WhatsApp message (under 25 words). Act as ${if (companionRole == "admin") "the Creator" else "the Guest"}. Put nice emojis."
            val replyMessage = withContext(Dispatchers.IO) { fetchGeminiResponseOrFallback(prompt, messageText) }
            val letters = replyMessage.length
            val dynamicDelay = (letters * 45).coerceIn(1200, 4200).toLong()
            delay(dynamicDelay)

            sendMessage(roomId, companionRole, replyMessage, "text")
            updateStatus(roomId, companionRole, isOnline = true, isTyping = false)
            delay(5000)
            updateStatus(roomId, companionRole, isOnline = false, isTyping = false)
        }
    }

    private fun fetchGeminiResponseOrFallback(prompt: String, fallbackIncoming: String): String {
        val key = BuildConfig.GEMINI_API_KEY
        if (key.isNotEmpty() && key != "MY_GEMINI_API_KEY") {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$key")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val requestObj = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(JSONObject().apply {
                        put("parts", org.json.JSONArray().put(JSONObject().apply { put("text", prompt) }))
                    }))
                }

                conn.outputStream.use { os -> os.write(requestObj.toString().toByteArray()) }

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    return json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
                }
            } catch (e: Exception) {
                Log.e("ChatRepository", "Failed calling Gemini API", e)
            }
        }

        val lower = fallbackIncoming.lowercase().trim()
        return when {
            lower.contains("hello") || lower.contains("hi") -> "Hey there! 👋 Excited to test this secure channel out. How are things looking?"
            lower.contains("how are you") -> "Doing amazing! This offline double-tap heart feature is smooth as butter. 😊 What about you?"
            lower.contains("link") || lower.contains("code") -> "The invite link and secret codes rotated! This app is super secure. 🔒"
            lower.contains("image") || lower.contains("pic") -> "Awesome, I see image sharing has beautiful support on both side angles! 📸 Let me double-tap that."
            lower.contains("voice") || lower.contains("audio") || lower.contains("listen") -> "Super cool voice message support! Dynamic read receipts are working instantly. ✓✓"
            lower.contains("reaction") || lower.contains("heart") || lower.contains("like") -> "Yes! Double click or double tap to 💗 works instantly, and reacts persist correctly. Solid logic! 🌸"
            lower.contains("delete") || lower.contains("clear") -> "Understood, delete wipes messages permanently from both ends. Highly secure! 🛡️"
            else -> listOf(
                "Got it! Thanks for sharing this private channel prompt. 👍",
                "Wow, that's incredibly responsive! Really clean design concept.",
                "Let's test replying to this message! Hold-click or swipe to thread it. 🤙",
                "I'm keeping my companion window active to secure the mock-ticks.",
                "The system status tick matches WhatsApp perfectly on mobile scale! 📱",
                "Fascinating. What's our next test step, Admin?"
            ).random()
        }
    }
}
