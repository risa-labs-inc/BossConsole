# Workspace System

## Overview

The workspace system allows users to save and load different split panel layouts in BOSS. Each workspace can define:
- The split panel structure (vertical/horizontal splits)
- Tabs open in each panel
- Tab types (browser, terminal, editor)

## Components

### 1. Data Model (`LayoutWorkspace.kt`)
- `TabConfig`: Represents a single tab (type, title, URL/file path)
- `PanelConfig`: Represents a panel with multiple tabs
- `SplitConfig`: Sealed class representing the split structure
  - `SinglePanel`: A panel with tabs
  - `VerticalSplit`: Left/right split
  - `HorizontalSplit`: Top/bottom split
- `LayoutWorkspace`: Complete layout with name, description, and structure

### 2. Workspace Manager (`WorkspaceManager.kt`)
- Manages loading, saving, and tracking workspaces
- Tracks dirty state (modified since last save)
- Handles import/export to JSON

### 3. UI Components
- `WorkspaceButton`: Dropdown button in the top bar
  - Shows current workspace name
  - Red dot indicator when dirty
  - Menu options: Load workspaces, Save, Save As, Reset

### 4. Workspace Applier (`WorkspaceApplier.kt`)
- Applies a workspace to the split view
- Creates panels and tabs according to the workspace
- Handles recursive split creation

### 5. Workspace Extractor (`WorkspaceExtractor.kt`)
- Extracts current layout into a workspace
- Captures all panels, splits, and tabs

## Predefined Workspaces

1. **PriorAuth**: Prior authorization workflow
   - Left: PA Dashboard
   - Top-right: OncoEMR
   - Bottom-right: CoverMyMeds, OneHealthcareID

2. **Designer**: Design tools workspace
   - Left: Figma
   - Top-right: Canva
   - Bottom-right: Notion

3. **Coder**: Development workspace
   - Top-left: GitHub
   - Bottom-left: Terminal
   - Top-right: Stack Overflow
   - Bottom-right: Code editor

4. **Mail**: Communication workspace
   - Left: Gmail
   - Top-right: LinkedIn
   - Bottom-right: X (Twitter)

## Usage

1. Click the workspace button (after Git button in top bar)
2. Select a predefined workspace or "Open from File..."
3. The layout will be applied automatically
4. When you modify tabs, the red dot appears
5. Save your changes with "Save Workspace" or "Save As..."

## File Format

Workspaces are stored as JSON:

```json
{
  "name": "My Workspace",
  "description": "Custom layout",
  "layout": {
    "type": "VerticalSplit",
    "left": {
      "type": "SinglePanel",
      "panel": {
        "id": "left",
        "tabs": [
          {
            "type": "browser",
            "title": "Google",
            "url": "https://google.com"
          }
        ]
      }
    },
    "right": {
      "type": "SinglePanel",
      "panel": {
        "id": "right",
        "tabs": [
          {
            "type": "terminal",
            "title": "Terminal"
          }
        ]
      }
    }
  }
}
```