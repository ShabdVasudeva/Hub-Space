package apw.android.hubspace.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import apw.android.hubspace.auth.AuthRepository
import apw.android.hubspace.auth.AuthResult
import apw.android.hubspace.auth.signInWithGoogle
import apw.android.hubspace.backend.GeminiApiClient
import apw.android.hubspace.backend.HubSpaceRepository
import apw.android.hubspace.backend.data.FeedItem
import apw.android.hubspace.backend.data.FeedType
import apw.android.hubspace.ui.components.AnnouncementItem
import apw.android.hubspace.ui.components.DaySummaryState
import apw.android.hubspace.ui.components.EventItem
import apw.android.hubspace.ui.components.HighlightType
import apw.android.hubspace.ui.components.SummaryHighlight
import com.google.firebase.Firebase
import com.google.firebase.vertexai.FirebaseVertexAI
import com.google.firebase.vertexai.vertexAI
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    data class Success(val userId: String) : AuthUiState()
    data class Unauthorized(val email: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

class AuthViewModel(
    private val repository: AuthRepository = AuthRepository(),
    private val hubSpaceRepo: HubSpaceRepository = HubSpaceRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    var memberCount by mutableIntStateOf(0)
        private set
    var latestEvent by mutableStateOf<EventItem?>(null)
        private set
    var latestAnnouncement by mutableStateOf<AnnouncementItem?>(null)
        private set
    var userTeam by mutableStateOf("")
        private set
    var teamsList by mutableStateOf<List<String>>(emptyList())
        private set
    var teamGithubUserName by mutableStateOf("apw-android")
        private set
    var feedList by mutableStateOf<List<FeedItem>>(emptyList())
        private set
    var isLoadingFeed by mutableStateOf(false)
        private set
    var daySummaryState by mutableStateOf<DaySummaryState>(DaySummaryState.Idle)
        private set

    private fun formatTimestamp(timestampObj: Any?): String {
        return if (timestampObj is com.google.firebase.Timestamp) {
            val netDate = timestampObj.toDate()
            android.text.format.DateUtils.getRelativeTimeSpanString(
                netDate.time,
                System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS
            ).toString()
        } else {
            "Recently"
        }
    }

    fun onGoogleSignInClicked(context: Context, webClientId: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            val credential = signInWithGoogle(
                context = context, webClientId = webClientId
            )

            if (credential == null) {
                _uiState.value = AuthUiState.Error("Google Sign-In canceled or failed")
                return@launch
            } else {
                when (val result = repository.authenticateAndCheckWhitelist(credential.idToken)) {
                    is AuthResult.Success -> {
                        _uiState.value = AuthUiState.Success(result.email)
                    }

                    is AuthResult.Unauthorized -> {
                        _uiState.value = AuthUiState.Unauthorized(result.message)
                    }

                    is AuthResult.Failed -> {
                        _uiState.value = AuthUiState.Error(result.message)
                    }
                }
            }
        }
    }

    private suspend fun fetchInitialData(email: String) {
        try {
            val teams = hubSpaceRepo.getUserTeams(email)
            teamsList = teams

            if (teams.isNotEmpty()) {
                val activeTeam = teams.firstOrNull() ?: "APW Labs"

                userTeam = activeTeam
                teamGithubUserName = hubSpaceRepo.getGithubUsername(activeTeam)
                if (activeTeam.isNotEmpty()) {
                    val teamInfo = hubSpaceRepo.getTeamMembersInfo(activeTeam)
                    memberCount = teamInfo.memberCount

                    val eventMap = hubSpaceRepo.getLatestEvent(activeTeam)
                    latestEvent = eventMap?.let {
                        val timestamp = it["at"] as? com.google.firebase.Timestamp
                        val timeMillis = timestamp?.toDate()?.time ?: 0L

                        val expiresAt = it["expiresAt"] as? com.google.firebase.Timestamp
                        val isExpired =
                            expiresAt != null && expiresAt.toDate().before(java.util.Date())

                        if (!isExpired) {
                            EventItem(
                                title = it["title"] as? String ?: "",
                                description = it["description"] as? String ?: "",
                                timeMillis = timeMillis
                            )
                        } else {
                            null
                        }
                    }

                    val annMap = hubSpaceRepo.getLatestAnnouncement(activeTeam)
                    latestAnnouncement = annMap?.let {
                        val title = it["title"] as? String ?: ""
                        val body = it["body"] as? String ?: ""
                        val by = it["by"] as? String ?: ""
                        val pin = it["pin"] as? Boolean ?: false

                        val expiresTimestamp = it["expiresAt"] as? com.google.firebase.Timestamp
                        val isExpired = expiresTimestamp != null && expiresTimestamp.toDate()
                            .before(java.util.Date())

                        if (!isExpired) {
                            AnnouncementItem(
                                title = title,
                                body = body,
                                by = by,
                                expiresAt = expiresTimestamp?.toDate().toString(),
                                pin = pin
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.message?.let { Log.e("AuthViewModel", it) }
        }
    }

    fun checkSessionOnStart() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            val currentUser = repository.auth.currentUser
            if (currentUser == null) {
                _uiState.value = AuthUiState.Idle
                return@launch
            }

            when (val result = repository.checkStatus()) {
                is AuthResult.Success -> {
                    fetchInitialData(result.email)
                    _uiState.value = AuthUiState.Success(result.email)
                }

                is AuthResult.Unauthorized -> {
                    _uiState.value = AuthUiState.Unauthorized(currentUser.email ?: "")
                }

                is AuthResult.Failed -> {
                    _uiState.value = AuthUiState.Error(result.message)
                }
            }
        }
    }

    fun recheckWhitelistStatus() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            when (val result = repository.checkStatus()) {
                is AuthResult.Success -> {
                    _uiState.value = AuthUiState.Success(result.email)
                }

                is AuthResult.Unauthorized -> {
                    _uiState.value =
                        AuthUiState.Error("Still not whitelisted. Contact admin if already added.")
                }

                is AuthResult.Failed -> {
                    _uiState.value = AuthUiState.Error(result.message)
                }
            }
        }
    }

    fun switchTeam(newTeam: String) {
        if (newTeam == userTeam) return

        userTeam = newTeam
        viewModelScope.launch {
            try {
                teamGithubUserName = hubSpaceRepo.getGithubUsername(newTeam)
                val teamInfo = hubSpaceRepo.getTeamMembersInfo(newTeam)
                memberCount = teamInfo.memberCount

//                val annMap = hubSpaceRepo.getLatestAnnouncement(newTeam)
//                latestAnnouncement = annMap?.let {
//                    AnnouncementItem(
//                        title = it["title"] as? String ?: "",
//                        body = it["body"] as? String ?: "",
//                        by = it["by"] as? String ?: "",
//                        pin = it["pin"] as? Boolean ?: false
//                    )
//                }

                val annMap = hubSpaceRepo.getLatestAnnouncement(newTeam)
                latestAnnouncement = annMap?.let {
                    val title = it["title"] as? String ?: ""
                    val body = it["body"] as? String ?: ""
                    val by = it["by"] as? String ?: ""
                    val pin = it["pin"] as? Boolean ?: false

                    val expiresTimestamp = it["expiresAt"] as? com.google.firebase.Timestamp
                    val isExpired = expiresTimestamp != null && expiresTimestamp.toDate()
                        .before(java.util.Date())

                    if (!isExpired) {
                        AnnouncementItem(
                            title = title,
                            body = body,
                            by = by,
                            expiresAt = expiresTimestamp?.toDate().toString(),
                            pin = pin
                        )
                    } else {
                        null
                    }
                }

                val eventMap = hubSpaceRepo.getLatestEvent(newTeam)
                latestEvent = eventMap?.let {
                    val timestamp = it["at"] as? com.google.firebase.Timestamp
                    val timeMillis = timestamp?.toDate()?.time ?: 0L

                    val expiresAt = it["expiresAt"] as? com.google.firebase.Timestamp
                    val isExpired = expiresAt != null && expiresAt.toDate().before(java.util.Date())
                    if (!isExpired) {
                        EventItem(
                            title = it["title"] as? String ?: "",
                            description = it["description"] as? String ?: "",
                            timeMillis = timeMillis
                        )
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("ERR", e.message.toString())
            }
        }
    }

    fun fetchTeamFeed() {
        viewModelScope.launch {
            isLoadingFeed = true

            try {
                val announcementsDeferred = async { hubSpaceRepo.getAnnouncements(userTeam) }
                val eventsDeferred = async { hubSpaceRepo.getEvents(userTeam) }

                val announcements = announcementsDeferred.await()
                val events = eventsDeferred.await()

                val mappedAnnouncements = announcements.map { map ->
                    FeedItem(
                        id = map["id"]?.toString() ?: java.util.UUID.randomUUID().toString(),
                        title = map["title"]?.toString() ?: "Announcement",
                        body = map["body"]?.toString() ?: "",
                        postedBy = map["author"]?.toString() ?: "Team Lead",
                        timeStamp = formatTimestamp(map["at"]),
                        type = FeedType.ANNOUNCEMENT,
                        isPinned = map["pin"] as? Boolean ?: false
                    )
                }

                val mappedEvents = events.map { map ->
                    FeedItem(
                        id = map["id"]?.toString() ?: java.util.UUID.randomUUID().toString(),
                        title = map["title"]?.toString() ?: "Team Event",
                        body = map["description"]?.toString() ?: map["body"]?.toString() ?: "",
                        postedBy = map["organizer"]?.toString() ?: "Coordinator",
                        timeStamp = formatTimestamp(map["at"]),
                        type = FeedType.EVENT,
                        isPinned = false
                    )
                }

                feedList = (mappedAnnouncements + mappedEvents).sortedByDescending { it.isPinned }
            } catch (e: Exception) {
                Log.e("AuthVM", "Error fetching team feed", e)
            } finally {
                isLoadingFeed = false
            }
        }
    }

    fun generateDaySummary(
        pendingTasksCount: Int,
        eventTitle: String,
        announcementTitle: String
    ) {
        viewModelScope.launch {
            daySummaryState = DaySummaryState.Loading
            try {
                val prompt = """
                You are HubSpace AI, a smart team collaboration assistant. 
                Summarize the user's day in exactly 2 encouraging, professional sentences. 
                Context:
                - Team: $userTeam
                - Pending Tasks: $pendingTasksCount
                - Upcoming Event: ${if (eventTitle.isNotEmpty()) eventTitle else "None"}
                - Latest Pinned Announcement: ${if (announcementTitle.isNotEmpty()) announcementTitle else "None"}
                
                Keep it action-oriented and engaging for a developer team. Do not add markdown bullet points.
            """.trimIndent()

                val summaryText = GeminiApiClient.generateText(prompt)

                val highlights = mutableListOf<SummaryHighlight>()
                if (pendingTasksCount > 0) {
                    highlights.add(SummaryHighlight(HighlightType.Tasks, "$pendingTasksCount active in your team's queue"))
                }
                if (eventTitle.isNotEmpty()) {
                    highlights.add(SummaryHighlight(HighlightType.Event, eventTitle))
                }
                if (announcementTitle.isNotEmpty()) {
                    highlights.add(SummaryHighlight(HighlightType.Notice, announcementTitle))
                }

                daySummaryState = DaySummaryState.Success(
                    text = summaryText,
                    highlights = highlights,
                    generatedAt = "Just now"
                )
            } catch (e: Exception) {
                Log.e("AuthVM", "Error in summary generation", e)
                daySummaryState = DaySummaryState.Error("We couldn't generate your brief. Please try again.")
            }
        }
    }

    fun resetSummaryState() {
        daySummaryState = DaySummaryState.Idle
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }
}