package ai.rever.boss.components.tabs_navigation

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update

class TabsNavigation<C : Any>(
    private val initial: List<C> = emptyList(),
    private val initialActive: Int = -1
) {
    private val _tabs = MutableValue(TabsState(tabs = initial, activeIndex = initialActive))
    val state: Value<TabsState<C>> = _tabs

    fun addTab(config: C): Int {
        val newIndex = _tabs.value.tabs.size
        _tabs.update {
            it.copy(
                tabs = it.tabs + config,
                activeIndex = newIndex
            )
        }
        return newIndex
    }

    fun removeTab(index: Int) {
        _tabs.update { currentState ->
            // Check if index is valid
            if (index !in currentState.tabs.indices) {
                return@update currentState
            }
            
            val newTabs = currentState.tabs.toMutableList().apply { removeAt(index) }
            val newActiveIndex = when {
                newTabs.isEmpty() -> -1
                index == currentState.activeIndex -> minOf(index, newTabs.size - 1)
                index < currentState.activeIndex -> currentState.activeIndex - 1
                else -> currentState.activeIndex
            }
            currentState.copy(tabs = newTabs, activeIndex = newActiveIndex)
        }
    }

    fun selectTab(index: Int) {
        _tabs.update { it.copy(activeIndex = index) }
    }
    
    fun updateTab(index: Int, config: C) {
        _tabs.update { currentState ->
            val newTabs = currentState.tabs.toMutableList()
            if (index in newTabs.indices) {
                newTabs[index] = config
            }
            currentState.copy(tabs = newTabs)
        }
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return

        _tabs.update { currentState ->
            if (fromIndex !in currentState.tabs.indices || toIndex !in currentState.tabs.indices) {
                return@update currentState
            }

            val newTabs = currentState.tabs.toMutableList()
            val tab = newTabs.removeAt(fromIndex)
            newTabs.add(toIndex, tab)

            // Adjust activeIndex based on the move
            val newActiveIndex = when {
                currentState.activeIndex == fromIndex -> toIndex
                fromIndex < currentState.activeIndex && toIndex >= currentState.activeIndex ->
                    currentState.activeIndex - 1
                fromIndex > currentState.activeIndex && toIndex <= currentState.activeIndex ->
                    currentState.activeIndex + 1
                else -> currentState.activeIndex
            }

            currentState.copy(tabs = newTabs, activeIndex = newActiveIndex)
        }
    }

    data class TabsState<C>(
        val tabs: List<C> = emptyList(),
        val activeIndex: Int = -1
    ) {
        val activeTab: C? = tabs.getOrNull(activeIndex)
    }
}

