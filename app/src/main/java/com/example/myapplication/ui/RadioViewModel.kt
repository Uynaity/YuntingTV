package com.example.myapplication.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.RadioRepository
import com.example.myapplication.data.model.Category
import com.example.myapplication.data.model.Channel
import com.example.myapplication.data.model.Province
import com.example.myapplication.data.prefs.UserPreferences
import com.example.myapplication.player.RadioPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 整个广播界面的 UI 状态。 */
data class RadioUiState(
    val provinces: List<Province> = emptyList(),
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val selectedProvinceCode: Long = UserPreferences.DEFAULT_PROVINCE_CODE,
    val selectedCategoryId: String = UserPreferences.DEFAULT_CATEGORY_ID,
    val currentChannel: Channel? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isLoadingChannels: Boolean = false,
    val isLoadingFilters: Boolean = true,
    val error: String? = null,
)

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = RadioRepository()
    private val prefs = UserPreferences(app)
    private val player = RadioPlayer(app)

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    init {
        // 同步播放器状态
        viewModelScope.launch {
            player.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            player.isBuffering.collect { buffering ->
                _uiState.update { it.copy(isBuffering = buffering) }
            }
        }
        bootstrap()
    }

    /** 加载筛选项 + 恢复上次选择 + 拉取电台。 */
    private fun bootstrap() {
        viewModelScope.launch {
            val saved = prefs.selection.first()
            _uiState.update {
                it.copy(
                    selectedProvinceCode = saved.provinceCode,
                    selectedCategoryId = saved.categoryId,
                    isLoadingFilters = true,
                    error = null,
                )
            }
            try {
                val provinces = repository.fetchProvinces()
                val categories = repository.fetchCategories()
                _uiState.update {
                    it.copy(
                        provinces = provinces,
                        categories = categories,
                        isLoadingFilters = false,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingFilters = false, error = e.message ?: "加载筛选项失败")
                }
            }
            loadChannels()
        }
    }

    fun selectProvince(provinceCode: Long) {
        if (provinceCode == _uiState.value.selectedProvinceCode) return
        _uiState.update { it.copy(selectedProvinceCode = provinceCode) }
        persistSelection()
        loadChannels()
    }

    fun selectCategory(categoryId: String) {
        if (categoryId == _uiState.value.selectedCategoryId) return
        _uiState.update { it.copy(selectedCategoryId = categoryId) }
        persistSelection()
        loadChannels()
    }

    private fun persistSelection() {
        val s = _uiState.value
        viewModelScope.launch {
            prefs.saveSelection(s.selectedProvinceCode, s.selectedCategoryId)
        }
    }

    fun loadChannels() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingChannels = true, error = null) }
            try {
                val channels = repository.fetchChannels(
                    categoryId = state.selectedCategoryId,
                    provinceCode = state.selectedProvinceCode,
                )
                _uiState.update { it.copy(channels = channels, isLoadingChannels = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoadingChannels = false, error = e.message ?: "加载电台失败")
                }
            }
        }
    }

    /** 选中并播放一个电台。 */
    fun playChannel(channel: Channel) {
        _uiState.update { it.copy(currentChannel = channel) }
        player.play(channel.playUrlLow)
    }

    fun togglePlayPause() {
        if (_uiState.value.currentChannel == null) return
        player.togglePlayPause()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
