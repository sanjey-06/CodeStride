    package com.sanjey.codestride.viewmodel

    import android.util.Log
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.sanjey.codestride.common.UiState
    import com.sanjey.codestride.data.model.Question
    import com.sanjey.codestride.data.model.Quiz
    import com.sanjey.codestride.data.repository.AiGenerationRepository
    import com.sanjey.codestride.data.repository.FirebaseRepository
    import com.sanjey.codestride.data.repository.ModuleRepository
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.launch
    import javax.inject.Inject

    enum class QuizResultState {
        None, Passed, Failed
    }

    @HiltViewModel
    class QuizViewModel @Inject constructor(
        private val firebaseRepository: FirebaseRepository,
        private val aiGenerationRepository: AiGenerationRepository,
        private val moduleRepository: ModuleRepository
    ) : ViewModel() {

        private val _questionsState = MutableStateFlow<UiState<List<Question>>>(UiState.Idle)
        val questionsState: StateFlow<UiState<List<Question>>> = _questionsState

        private val _quizDetailsState = MutableStateFlow<UiState<Quiz>>(UiState.Idle)
        val quizDetailsState: StateFlow<UiState<Quiz>> = _quizDetailsState

        private val _quizResultState = MutableStateFlow(QuizResultState.None)
        val quizResultState: StateFlow<QuizResultState> = _quizResultState

        private var passingScore: Int = 0

        private val _quizState = MutableStateFlow<UiState<Pair<Quiz, List<Question>>>>(UiState.Idle)


        // ✅ Internal state for current quiz progress
        private val _currentIndex = MutableStateFlow(0)
        val currentIndex: StateFlow<Int> = _currentIndex

        private val _selectedOption = MutableStateFlow<String?>(null)
        val selectedOption: StateFlow<String?> = _selectedOption

        private val _score = MutableStateFlow(0)
        val score: StateFlow<Int> = _score

        val currentQuestion: StateFlow<Question?> = MutableStateFlow(null)

        // ✅ Load quiz details and questions
        fun loadQuizData(roadmapId: String, moduleId: String, quizId: String) {
            viewModelScope.launch {
                _quizDetailsState.value = UiState.Loading
                _questionsState.value = UiState.Loading
                try {
                    val quiz = firebaseRepository.getQuizDetails(roadmapId, moduleId, quizId)
                    if (quiz != null) {
                        passingScore = quiz.passingScore
                        _quizDetailsState.value = UiState.Success(quiz)
                    } else {
                        _quizDetailsState.value = UiState.Error("Quiz details not found")
                    }

                    val questions = firebaseRepository.getQuestionsByQuiz(roadmapId, moduleId, quizId)
                    if (questions.isNotEmpty()) {
                        _questionsState.value = UiState.Success(questions)
                        (currentQuestion as MutableStateFlow).value = questions.first()
                    } else {
                        _questionsState.value = UiState.Empty
                    }
                } catch (e: Exception) {
                    _quizDetailsState.value = UiState.Error("Failed to load quiz")
                    _questionsState.value = UiState.Error("Failed to load questions")
                }
            }
        }

        fun selectOption(option: String) {
            _selectedOption.value = option
        }

        fun submitCurrentAnswer(roadmapId: String, moduleId: String, roadmapViewModel: RoadmapViewModel) {
            val questions = (questionsState.value as? UiState.Success)?.data ?: return
            val currentQ = questions.getOrNull(_currentIndex.value) ?: return

            // ✅ Check answer
            if (_selectedOption.value == currentQ.correctAnswer) {
                _score.value += 1
            }

            if (_currentIndex.value < questions.size - 1) {
                _currentIndex.value += 1
                (currentQuestion as MutableStateFlow).value = questions[_currentIndex.value]
                _selectedOption.value = null
                Log.d("QUIZ_DEBUG", "Next Question → index=${_currentIndex.value}, score=${_score.value}")
            } else {
                // ✅ Final Question Completed
                if (_score.value >= passingScore) {
                    _quizResultState.value = QuizResultState.Passed
                    Log.d("QUIZ_DEBUG", "Quiz Passed → score=${_score.value}, passingScore=$passingScore")

                    // ✅ Unlock next module (Update Firestore Progress)
                    roadmapViewModel.updateProgress(roadmapId, moduleId)
                    Log.d("QUIZ_DEBUG", "updateProgress() called → roadmapId=$roadmapId, moduleId=$moduleId")
                } else {
                    _quizResultState.value = QuizResultState.Failed
                    Log.d("QUIZ_DEBUG", "Quiz Failed → score=${_score.value}, passingScore=$passingScore")
                }
            }
        }


        fun resetQuiz() {
            _currentIndex.value = 0
            _score.value = 0
            _selectedOption.value = null
            _quizResultState.value = QuizResultState.None
        }

        fun saveBadge(userId: String, title: String, image: String, roadmapId: String, moduleId: String) {
            viewModelScope.launch {
                try {
                    firebaseRepository.saveBadge(userId, title, image, roadmapId, moduleId)
                } catch (e: Exception) {
                    Log.e("BADGE_DEBUG", "Failed to save badge: ${e.message}")
                }
            }
        }


        fun generateQuizIfNeeded(roadmapId: String, moduleId: String, quizId: String) {
            viewModelScope.launch {
                Log.d("QUIZ_DEBUG", "🚀 generateQuizIfNeeded called → roadmapId=$roadmapId, moduleId=$moduleId, quizId=$quizId")

                _quizState.value = UiState.Loading
                try {
                    val quizDetails = firebaseRepository.getQuizDetails(roadmapId, moduleId, quizId)
                    val questions = firebaseRepository.getQuestionsByQuiz(roadmapId, moduleId, quizId)
                    Log.d("QUIZ_DEBUG", "📡 Checking Firestore for existing quiz...")

                    if (quizDetails == null || questions.isEmpty()) {
                        val module = moduleRepository.getModuleById(roadmapId, moduleId)
                        val generatedQuestions = aiGenerationRepository.generateQuiz(
                            roadmapId, // topic
                            module?.title ?: "Learning"
                        )

                        if (generatedQuestions.isNotEmpty()) {
                            // 🎯 Map moduleN -> quizN (default_ai_badges/quizN)
                            val badgeId = "quiz${moduleId.removePrefix("module")}"
                            val badge = firebaseRepository.getBadgeById(badgeId) // make sure this reads default_ai_badges

                            val aiQuiz = Quiz(
                                id = quizId,
                                passingScore = 3, // fixed for now
                                totalQuestions = generatedQuestions.size,
                                badgeImage = badge?.imageUrl ?: "",
                                badgeTitle = badge?.title ?: "",
                                badgeDescription = badge?.description ?: ""
                            )

                            // ✅ 1) Optimistic UI: show immediately to avoid "No questions available"
                            _quizState.value = UiState.Success(aiQuiz to generatedQuestions)

                            // ✅ 2) Save in background (don’t block UI)
                            viewModelScope.launch {
                                try {
                                    firebaseRepository.saveAIQuiz(
                                        roadmapId,
                                        moduleId,
                                        quizId,
                                        aiQuiz,
                                        generatedQuestions
                                    )
                                    Log.d("QUIZ_DEBUG", "✅ Generated and saved quiz with ${generatedQuestions.size} questions")
                                } catch (e: Exception) {
                                    Log.e("QUIZ_DEBUG", "❌ Save failed: ${e.message}")
                                }
                            }

                        } else {
                            _quizState.value = UiState.Error("Failed to generate quiz")
                        }
                    } else {
                        _quizState.value = UiState.Success(quizDetails to questions)
                    }

                } catch (e: Exception) {
                    Log.e("AI_QUIZ_GEN", "❌ Error: ${e.message}")
                    _quizState.value = UiState.Error("Something went wrong: ${e.message}")
                }
            }
        }





    }
