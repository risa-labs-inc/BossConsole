package ai.rever.boss.topofmind

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * State management for tree expansion in TopOfMind views.
 * This is host-app state, not plugin state.
 */
object TabTreeState {
    private val _expandedNodes = MutableStateFlow<Set<String>>(emptySet())
    val expandedNodes: StateFlow<Set<String>> = _expandedNodes

    // Track which workspaces have been modified
    private val _modifiedWorkspaces = MutableStateFlow<Set<String>>(emptySet())
    val modifiedWorkspaces: StateFlow<Set<String>> = _modifiedWorkspaces

    fun toggleExpansion(nodeId: String) {
        val current = _expandedNodes.value.toMutableSet()
        if (current.contains(nodeId)) {
            current.remove(nodeId)
        } else {
            current.add(nodeId)
        }
        _expandedNodes.value = current
    }

    fun initializeDefaultExpansion(nodes: List<TabTreeNode>) {
        // Expand all workspace nodes by default
        val workspaceNodes = nodes.filterIsInstance<TabTreeNode.WorkspaceNode>()
        _expandedNodes.value = workspaceNodes.map { it.id }.toSet()
    }

    fun markWorkspaceAsModified(workspaceId: String) {
        val current = _modifiedWorkspaces.value.toMutableSet()
        current.add(workspaceId)
        _modifiedWorkspaces.value = current
    }

    fun markWorkspaceAsSaved(workspaceId: String) {
        val current = _modifiedWorkspaces.value.toMutableSet()
        current.remove(workspaceId)
        _modifiedWorkspaces.value = current
    }

    fun isWorkspaceModified(workspaceId: String): Boolean {
        return _modifiedWorkspaces.value.contains(workspaceId)
    }

    // Track expanded sections (workspace:sectionPath) - sections collapsed by default
    private val _expandedSections = MutableStateFlow<Set<String>>(emptySet())
    val expandedSections: StateFlow<Set<String>> = _expandedSections

    fun toggleSectionExpansion(sectionKey: String) {
        val current = _expandedSections.value.toMutableSet()
        if (current.contains(sectionKey)) {
            current.remove(sectionKey)
        } else {
            current.add(sectionKey)
        }
        _expandedSections.value = current
    }

    fun isSectionExpanded(sectionKey: String): Boolean {
        return _expandedSections.value.contains(sectionKey)
    }
}

/**
 * Global state for tracking all active tabs across workspaces.
 * This is host-app state used by dialogs and search functionality.
 */
object TopOfMindStateHolder {
    private val _activeTabs = MutableStateFlow<List<ActiveTab>>(emptyList())
    val activeTabs: StateFlow<List<ActiveTab>> = _activeTabs

    fun updateActiveTabs(tabs: List<ActiveTab>) {
        _activeTabs.value = tabs
    }
}
