---
name: apple-hig-design
description: Implements Apple Human Interface Guidelines for Compose Multiplatform desktop UI.
triggers:
  - "apple-design"
  - "apple-hig"
  - "layout"
  - "theme"
  - "styling"
---
# Apple HIG Rules
- Continuous Radii: Always use squircle-style rounded corners ('RoundedCornerShape(12.dp)' to '16.dp').
- Frosted Surfaces: Dark slate backgrounds ('#0E1217', '#12161F') layered with subtle borders ('1.dp' at 'Color.White.copy(0.08f)').
- Aspect Ratio Containers: Wrap grid boards in 'Modifier.aspectRatio(1f)' inside 'BoxWithConstraints' to support resizing from 300px to 900px.
- Typography & Badges: Use clean, uppercase pill tags with monospace metrics for steps, level counters, and optimal distance.
